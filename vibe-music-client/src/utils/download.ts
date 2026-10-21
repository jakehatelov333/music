/**
 * 触发浏览器下载。
 *
 * <p>`a[download]` 对跨域地址通常会被忽略，浏览器直接打开音频文件；
 * 先 fetch 成 Blob 再下载可以保留真正的「保存文件」行为。若媒体域没有
 * CORS，则回退到原链接，至少不让下载按钮完全无响应。
 */
export async function downloadMedia(url: string, filename: string) {
  if (!url) throw new Error('没有可下载的音频地址')

  const safeFilename = filename.replace(/[\\/:*?"<>|]/g, '_').trim() || 'audio'
  let objectUrl = ''

  const response = await fetch(url, { credentials: 'omit' })
  if (!response.ok) throw new Error(`下载失败（${response.status}）`)
  const blob = await response.blob()
  objectUrl = URL.createObjectURL(blob)
  try {
    triggerDownload(objectUrl, safeFilename)
  } finally {
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
  }
}

function triggerDownload(url: string, filename: string) {
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.rel = 'noopener'
  document.body.appendChild(link)
  link.click()
  link.remove()
}

/** 分享失败时复制链接；clipboard 不可用时回退到临时 textarea。 */
export async function copyText(text: string) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
    return
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  const copied = document.execCommand('copy')
  textarea.remove()
  if (!copied) throw new Error('复制失败')
}
