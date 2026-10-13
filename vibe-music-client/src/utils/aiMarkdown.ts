import MarkdownIt from 'markdown-it'

// 模型和历史内容均不可信：禁用 HTML、图片与链接，只保留文本排版。
const markdown = new MarkdownIt({ html: false, breaks: true, linkify: false })
markdown.disable(['image', 'link', 'autolink'])
markdown.renderer.rules.text = (tokens, index) => {
  const text = markdown.utils.escapeHtml(tokens[index].content)
  return text
    .replace(/《([^《》\n]+)》/g, '<strong>《$1》</strong>')
    .replace(
      /^(歌单名称|歌曲数量|歌手|专辑|风格|简介)([：:])/g,
      '<strong>$1$2</strong>'
    )
}

export const renderAiMarkdown = (text: string) => markdown.render(text)
