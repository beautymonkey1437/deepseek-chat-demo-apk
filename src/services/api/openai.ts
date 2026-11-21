import { ApiError, apiBaseUrl, readErrorResponse, request } from './client.js'
import { readServerSentEvents } from './sse.js'
import type {
  CreateMessageRequest,
  MessageParam,
  MessageStreamEvent,
  MessageUsage,
  StopReason,
} from './types.js'

/**
 * OpenAI Chat Completions 兼容实现：把应用使用的 Messages 请求转换成 Chat Completions，
 * 再把流式分片还原成 MessageStreamEvent，其余模块不需要知道协议差异。
 * Chat Completions 没有 Files API、服务端搜索与标题扩展，附件、搜索与自动标题不在本实现范围内。
 */

type OpenAiMessage = { role: 'system' | 'user' | 'assistant'; content: string }

type OpenAiRequest = {
  model: string
  messages: OpenAiMessage[]
  stream: true
  stream_options: { include_usage: true }
}

type OpenAiChunk = {
  id?: unknown
  model?: unknown
  choices?: unknown
  usage?: unknown
  error?: unknown
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value)

const asText = (value: unknown): string => (typeof value === 'string' ? value : '')

/** 提问里只有文本能跨协议：图片、文档按 file_id 引用，Chat Completions 无从解析。 */
const userText = (content: Extract<MessageParam, { role: 'user' }>['content']): string =>
  typeof content === 'string'
    ? content
    : content.map((block) => (block.type === 'text' ? block.text : '')).join('\n')

/** 历史回答只回传正文：思考、工具块与签名都是 Anthropic 协议特有的。 */
const assistantText = (blocks: Extract<MessageParam, { role: 'assistant' }>['content']): string =>
  blocks.map((block) => (block.type === 'text' ? block.text : '')).join('\n')

/** Messages 请求 → Chat Completions 请求；system、messages 之外的服务端扩展字段不带。 */
export const toOpenAiRequest = (body: CreateMessageRequest): OpenAiRequest => {
  const messages: OpenAiMessage[] = body.system ? [{ role: 'system', content: body.system }] : []
  for (const message of body.messages) {
    switch (message.role) {
      case 'latest_reminder':
        // 该角色是 Chat Engine 扩展，等价位置用一条 system 提示代替
        messages.push({ role: 'system', content: message.content })
        break
      case 'user':
        messages.push({ role: 'user', content: userText(message.content) })
        break
      case 'assistant':
        messages.push({ role: 'assistant', content: assistantText(message.content) })
        break
    }
  }
  return {
    model: body.model,
    messages,
    stream: true,
    stream_options: { include_usage: true },
  }
}

const STOP_REASONS: Record<string, StopReason> = {
  stop: 'end_turn',
  length: 'max_tokens',
  content_filter: 'refusal',
  tool_calls: 'tool_use',
  function_call: 'tool_use',
}

const readUsage = (value: unknown): MessageUsage | undefined => {
  if (!isRecord(value)) {
    return undefined
  }
  const input = value.prompt_tokens
  const output = value.completion_tokens
  if (typeof input !== 'number' || typeof output !== 'number') {
    return undefined
  }
  // 缓存用量是 Anthropic 协议字段，这里没有对应信息
  return {
    input_tokens: input,
    output_tokens: output,
    cache_creation_input_tokens: 0,
    cache_read_input_tokens: 0,
  }
}

/**
 * POST {base}/chat/completions（stream: true），把分片还原成 Messages 流式事件：
 * 正文与 `reasoning_content`（部分服务商的思考字段）分别对应 text / thinking 块，
 * finish_reason 对应停止原因，流结束（[DONE] 或连接关闭）时补结束事件。
 */
export async function* streamOpenAiMessage(
  apiKey: string,
  body: CreateMessageRequest,
  signal: AbortSignal
): AsyncGenerator<MessageStreamEvent> {
  const response = await request(`${apiBaseUrl()}/chat/completions`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify(toOpenAiRequest(body)),
    signal,
  })
  const contentType = response.headers.get('Content-Type') ?? ''
  if (!response.body || !contentType.includes('text/event-stream')) {
    throw await readErrorResponse(response, 'protocol', '')
  }

  let started = false
  let nextIndex = 0
  let thinkingIndex = -1
  let textIndex = -1
  let usage: MessageUsage | undefined
  let stopReason: StopReason = 'end_turn'

  for await (const { data } of readServerSentEvents(response.body, signal)) {
    if (data === '[DONE]') {
      break
    }
    let chunk: OpenAiChunk
    try {
      chunk = JSON.parse(data) as OpenAiChunk
    } catch {
      continue
    }
    if (!isRecord(chunk)) {
      continue
    }
    if (chunk.error !== undefined) {
      throw new ApiError('stream', isRecord(chunk.error) ? asText(chunk.error.message) : data)
    }
    if (!started) {
      started = true
      yield {
        type: 'message_start',
        message: { id: asText(chunk.id), model: asText(chunk.model) || body.model },
      }
    }
    usage = readUsage(chunk.usage) ?? usage
    const choice = Array.isArray(chunk.choices) ? chunk.choices.find(isRecord) : undefined
    if (!choice) {
      continue
    }
    const finish = choice.finish_reason
    if (typeof finish === 'string' && finish) {
      stopReason = STOP_REASONS[finish] ?? 'end_turn'
    }
    const delta = isRecord(choice.delta) ? choice.delta : {}
    const reasoning = asText(delta.reasoning_content) || asText(delta.reasoning)
    if (reasoning) {
      if (thinkingIndex < 0) {
        thinkingIndex = nextIndex++
        yield {
          type: 'content_block_start',
          index: thinkingIndex,
          content_block: { type: 'thinking', thinking: '', signature: '' },
        }
      }
      yield {
        type: 'content_block_delta',
        index: thinkingIndex,
        delta: { type: 'thinking_delta', thinking: reasoning },
      }
    }
    const content = asText(delta.content)
    if (content) {
      if (textIndex < 0) {
        // 思考块先于正文结束，两块不在同一时间线上
        if (thinkingIndex >= 0) {
          const closed = thinkingIndex
          thinkingIndex = -1
          yield { type: 'content_block_stop', index: closed }
        }
        textIndex = nextIndex++
        yield {
          type: 'content_block_start',
          index: textIndex,
          content_block: { type: 'text', text: '' },
        }
      }
      yield {
        type: 'content_block_delta',
        index: textIndex,
        delta: { type: 'text_delta', text: content },
      }
    }
  }

  if (!started) {
    throw new ApiError('interrupted', '')
  }
  if (thinkingIndex >= 0) {
    yield { type: 'content_block_stop', index: thinkingIndex }
  }
  if (textIndex >= 0) {
    yield { type: 'content_block_stop', index: textIndex }
  }
  yield { type: 'message_delta', delta: { stop_reason: stopReason, stop_sequence: null }, usage }
  yield { type: 'message_stop' }
}
