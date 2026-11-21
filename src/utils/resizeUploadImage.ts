// 上传图片的长边上限，单位为像素。
const MAX_IMAGE_EDGE = 4096

/**
 * 解码本地图片并读出它自身的像素尺寸。
 * 老 WebView 的 HTMLImageElement.decode() 可能一直不 settle，这里与 onload、超时一起抢先返回：
 * 尺寸拿不到时按 0 处理，调用方会用原图上传，不会把发送卡住。
 */
const readUploadImage = (blob: Blob) => {
  const image = new Image()
  const url = URL.createObjectURL(blob)
  let timer: number | undefined
  const settle = (): Promise<void> =>
    new Promise<void>((resolve) => {
      // 4 秒仍未解码完成就放弃等待：慢设备读大图可能确实需要一点时间，但更久通常是内核卡住
      timer = window.setTimeout(resolve, 4000)
      image.onload = () => resolve()
      image.onerror = () => resolve()
      const decoded = image.decode?.()
      if (decoded) {
        void decoded.then(resolve, resolve)
      }
    })
  image.src = url
  return Promise.resolve(settle()).then(() => {
    if (timer !== undefined) {
      window.clearTimeout(timer)
    }
    return {
      image,
      longestEdge: Math.max(Number(image.naturalWidth) || 0, Number(image.naturalHeight) || 0),
      release: () => URL.revokeObjectURL(url),
    }
  })
}

/** 复用旧 file_id 前，用本地原件检查当时上传的图片是否超限。 */
export const isUploadImageOversized = async (blob: Blob): Promise<boolean> => {
  const { longestEdge, release } = await readUploadImage(blob)
  release()
  return longestEdge > MAX_IMAGE_EDGE
}

/** 图片在尺寸上限内时返回原文件，超限时生成按比例缩小的上传副本。 */
export const resizeUploadImage = async (file: File, signal?: AbortSignal): Promise<File> => {
  signal?.throwIfAborted()
  const { image, longestEdge, release } = await readUploadImage(file)
  try {
    signal?.throwIfAborted()
    // 尺寸未知（解码失败或超时）时按原图上传，让服务端去判断
    if (longestEdge <= MAX_IMAGE_EDGE) {
      return file
    }

    // 宽和高都乘以「长边上限 ÷ 原图长边」，这样横图和竖图都能按原比例缩到同一上限。
    const scale = MAX_IMAGE_EDGE / longestEdge
    const canvas = document.createElement('canvas')
    // 画布宽高需要整数像素。极细长图的短边可能舍入为 0，因此至少保留 1 像素。
    canvas.width = Math.max(1, Math.round(image.naturalWidth * scale))
    canvas.height = Math.max(1, Math.round(image.naturalHeight * scale))
    try {
      const context = canvas.getContext('2d')
      if (!context) {
        throw new Error('Cannot resize image')
      }
      // drawImage 将整张图重采样到目标尺寸的画布上。动图在这一步只绘制一帧。
      context.drawImage(image, 0, 0, canvas.width, canvas.height)
      // toBlob 把缩放后的像素编码成文件数据。JPEG 和 WebP 优先沿用原格式，其余使用 PNG。
      const type = file.type === 'image/jpeg' || file.type === 'image/webp' ? file.type : 'image/png'
      const blob = await new Promise<Blob>((resolve, reject) => {
        canvas.toBlob(
          (result) => (result ? resolve(result) : reject(new Error('Cannot encode image'))),
          type,
          // 压缩质量
          1
        )
      })
      signal?.throwIfAborted()
      // 浏览器可能回退到 PNG，上传文件的后缀以实际编码为准。
      const extension = blob.type === 'image/jpeg' ? 'jpg' : blob.type.slice('image/'.length)
      const name = file.name.replace(/\.[^.]*$/, '') + '.' + extension
      return new File([blob], name, { type: blob.type, lastModified: file.lastModified })
    } finally {
      canvas.width = canvas.height = 0
    }
  } finally {
    release()
  }
}
