import { create } from 'zustand'
import type { TtsVoice } from './api/speech.js'
import { normalizeLocation, type UserLocation } from './location.js'

export type Locale = 'zh-CN' | 'en-US'

/** 主题偏好；默认跟随系统 */
export type ThemePreference = 'light' | 'dark' | 'system'

/** 接口协议：Anthropic Messages（DeepSeek 兼容）或 OpenAI Chat Completions */
export type ApiProtocol = 'anthropic' | 'openai'

/** 本机设置，存 localStorage：API Key、接口地址与协议、语言、主题、位置、朗读音色、深度思考与智能搜索开关、侧边栏收起状态 */
type Settings = {
  apiKey: string | null
  /** 自定义接口地址；null 表示使用构建期默认地址 */
  apiBaseUrl: string | null
  apiProtocol: ApiProtocol
  /** 自定义模型 ID；null 表示使用应用默认模型 */
  apiModel: string | null
  locale: Locale
  themePreference: ThemePreference
  location: UserLocation | null
  ttsVoice: TtsVoice
  thinking: boolean
  search: boolean
  siderCollapsed: boolean
}

const KEYS = {
  apiKey: 'chat-demo:api-key',
  apiBaseUrl: 'chat-demo:api-base-url',
  apiProtocol: 'chat-demo:api-protocol',
  apiModel: 'chat-demo:api-model',
  locale: 'chat-demo:locale',
  location: 'chat-demo:location',
  themePreference: 'chat-demo:theme-preference',
  ttsVoice: 'chat-demo:tts-voice',
  thinking: 'chat-demo:thinking',
  search: 'chat-demo:search',
  siderCollapsed: 'chat-demo:sider-collapsed',
} as const

const read = (key: string) => {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

const write = (key: string, value: string | null) => {
  try {
    if (value === null) {
      localStorage.removeItem(key)
    } else {
      localStorage.setItem(key, value)
    }
  } catch {
    // 存储不可用时只在内存中生效
  }
}

/** 位置选择模式跟随浏览器语言，与应用内选择的界面语言无关。 */
export const isChineseBrowser = (): boolean =>
  (typeof navigator === 'undefined' ? 'zh' : navigator.language).toLowerCase().startsWith('zh')

const initialLocale = (): Locale => {
  const saved = read(KEYS.locale)
  if (saved === 'zh-CN' || saved === 'en-US') {
    return saved
  }
  return isChineseBrowser() ? 'zh-CN' : 'en-US'
}

const initialLocation = (): UserLocation | null => {
  try {
    const location = normalizeLocation(JSON.parse(read(KEYS.location) ?? 'null'))
    if (!location) {
      return null
    }
    return isChineseBrowser()
      ? location.region === 'CN' && location.province
        ? location
        : null
      : { region: location.region }
  } catch {
    return null
  }
}

/** 未保存过时跟随系统 */
const initialThemePreference = (): ThemePreference => {
  const saved = read(KEYS.themePreference)
  return saved === 'light' || saved === 'dark' ? saved : 'system'
}

/** 未保存过时使用服务端默认音色 stella（海星） */
const initialTtsVoice = (): TtsVoice => (read(KEYS.ttsVoice) === 'tide' ? 'tide' : 'stella')

/** 接口地址规范化：去掉首尾空白与结尾斜杠；不是 http(s) 地址时返回 null */
export const normalizeApiBaseUrl = (value: string): string | null => {
  const trimmed = value.trim().replace(/\/+$/, '')
  if (!trimmed) {
    return null
  }
  try {
    const { protocol } = new URL(trimmed)
    return protocol === 'http:' || protocol === 'https:' ? trimmed : null
  } catch {
    return null
  }
}

/** 未保存或保存值不合法时使用构建期默认地址 */
const initialApiBaseUrl = (): string | null => {
  const saved = read(KEYS.apiBaseUrl)
  return saved ? normalizeApiBaseUrl(saved) : null
}

/** 未保存过时按 DeepSeek 兼容的 Anthropic Messages 协议发送 */
const initialApiProtocol = (): ApiProtocol =>
  read(KEYS.apiProtocol) === 'openai' ? 'openai' : 'anthropic'

/** 模型 ID 规范化：去掉首尾空白，空值表示用应用默认模型 */
export const normalizeApiModel = (value: string): string | null =>
  value.trim() || null

const initialApiModel = (): string | null => {
  const saved = read(KEYS.apiModel)
  return saved ? normalizeApiModel(saved) : null
}

export const useSettings = create<Settings>(() => ({
  apiKey: read(KEYS.apiKey),
  apiBaseUrl: initialApiBaseUrl(),
  apiProtocol: initialApiProtocol(),
  apiModel: initialApiModel(),
  locale: initialLocale(),
  location: initialLocation(),
  themePreference: initialThemePreference(),
  ttsVoice: initialTtsVoice(),
  thinking: read(KEYS.thinking) === 'true',
  search: read(KEYS.search) === 'true',
  siderCollapsed: read(KEYS.siderCollapsed) === 'true',
}))

export const setLocale = (locale: Locale) => {
  write(KEYS.locale, locale)
  useSettings.setState({ locale })
}

export const setLocation = (value: UserLocation | null) => {
  const location = normalizeLocation(value)
  write(KEYS.location, location ? JSON.stringify(location) : null)
  useSettings.setState({ location })
}

export const setThemePreference = (themePreference: ThemePreference) => {
  write(KEYS.themePreference, themePreference)
  useSettings.setState({ themePreference })
}

export const setTtsVoice = (ttsVoice: TtsVoice) => {
  write(KEYS.ttsVoice, ttsVoice)
  useSettings.setState({ ttsVoice })
}

export const setApiKey = (apiKey: string | null) => {
  write(KEYS.apiKey, apiKey)
  useSettings.setState({ apiKey })
}

export const setApiBaseUrl = (value: string | null) => {
  const apiBaseUrl = value ? normalizeApiBaseUrl(value) : null
  write(KEYS.apiBaseUrl, apiBaseUrl)
  useSettings.setState({ apiBaseUrl })
}

export const setApiProtocol = (apiProtocol: ApiProtocol) => {
  write(KEYS.apiProtocol, apiProtocol)
  useSettings.setState({ apiProtocol })
}

export const setApiModel = (value: string | null) => {
  const apiModel = value ? normalizeApiModel(value) : null
  write(KEYS.apiModel, apiModel)
  useSettings.setState({ apiModel })
}

export const setThinking = (thinking: boolean) => {
  write(KEYS.thinking, String(thinking))
  useSettings.setState({ thinking })
}

export const setSearch = (search: boolean) => {
  write(KEYS.search, String(search))
  useSettings.setState({ search })
}

export const setSiderCollapsed = (siderCollapsed: boolean) => {
  write(KEYS.siderCollapsed, String(siderCollapsed))
  useSettings.setState({ siderCollapsed })
}
