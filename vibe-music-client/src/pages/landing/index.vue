<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const landingRoot = ref<HTMLElement | null>(null)
const visual = ref<HTMLElement | null>(null)
const navScrolled = ref(false)
const isPlaying = ref(true)
const progress = ref(38)
let observer: IntersectionObserver | undefined
let frame = 0
let pointer = { x: 0, y: 0 }
let handleScroll: (() => void) | undefined

const navItems = [
  { label: '首页', target: 'hero' },
  { label: '功能特色', target: 'features' },
  { label: 'AI 助手', target: 'workflow' },
  { label: '技术架构', target: 'technology' },
]

const featureCards = [
  {
    icon: '✦',
    label: 'AI 音乐助手',
    title: '用一句话，说出你的音乐',
    copy: '自然语言理解你的情绪、场景与偏好，给出真正贴合当下的推荐。',
    accent: 'violet',
  },
  {
    icon: '◈',
    label: '智能发现',
    title: '越听越懂你的品味',
    copy: '从熟悉的旋律出发，探索那些你还没遇见、却会喜欢的声音。',
    accent: 'cyan',
  },
  {
    icon: '◉',
    label: '高品质播放器',
    title: '沉浸式聆听体验',
    copy: '清晰、流畅、克制的播放器，让每一个音符都回到中心位置。',
    accent: 'blue',
  },
  {
    icon: '⌁',
    label: '个性化歌单',
    title: '把灵感收藏起来',
    copy: '创建、收藏、分享你的专属歌单，让每一种心情都有声音可循。',
    accent: 'pink',
  },
]

const workflowNodes = [
  {
    step: '01',
    icon: '⌕',
    title: '用户问题',
    detail: '自然语言',
    body: '给我一些适合深夜学习的音乐',
  },
  {
    step: '02',
    icon: '✦',
    title: '大模型',
    detail: 'Qwen',
    body: '理解意图与情绪，生成检索策略',
  },
  {
    step: '03',
    icon: '⌁',
    title: '工具调用',
    detail: 'MusicTools',
    body: '搜索、筛选、组合真实音乐数据',
  },
  {
    step: '04',
    icon: '▦',
    title: '业务系统',
    detail: 'MySQL / Redis',
    body: '稳定访问音乐库与用户偏好',
  },
  {
    step: '05',
    icon: '↗',
    title: '生成回复',
    detail: '流式输出',
    body: '带着推荐理由，回答你的问题',
  },
]

const techStack = [
  'Spring Boot',
  'MyBatis-Plus',
  'Redis',
  'MySQL',
  'MinIO',
  'LangChain4j',
  'Qwen',
]

const scrollTo = (target: string) => {
  document
    .getElementById(target)
    ?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const enter = () => router.push('/login')

const togglePlaying = () => {
  isPlaying.value = !isPlaying.value
}

const handlePointer = (event: PointerEvent) => {
  if (
    window.matchMedia('(prefers-reduced-motion: reduce)').matches ||
    window.innerWidth < 900
  )
    return
  const rect = visual.value?.getBoundingClientRect()
  if (!rect) return
  pointer = {
    x: (event.clientX - rect.left) / rect.width - 0.5,
    y: (event.clientY - rect.top) / rect.height - 0.5,
  }
  if (!frame)
    frame = requestAnimationFrame(() => {
      if (visual.value) {
        visual.value.style.setProperty('--tilt-x', `${pointer.y * -3}deg`)
        visual.value.style.setProperty('--tilt-y', `${pointer.x * 4}deg`)
      }
      frame = 0
    })
}

const resetPointer = () => {
  if (visual.value) {
    visual.value.style.setProperty('--tilt-x', '0deg')
    visual.value.style.setProperty('--tilt-y', '0deg')
  }
}

onMounted(async () => {
  await nextTick()
  const root = landingRoot.value
  if (!root) return
  observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) entry.target.classList.add('is-visible')
      })
    },
    { threshold: 0.12 }
  )
  root
    .querySelectorAll('[data-reveal]')
    .forEach((element) => observer?.observe(element))
  handleScroll = () => {
    navScrolled.value = window.scrollY > 24
  }
  window.addEventListener('scroll', handleScroll, { passive: true })
  handleScroll()
})

onBeforeUnmount(() => {
  if (handleScroll) window.removeEventListener('scroll', handleScroll)
  observer?.disconnect()
  if (frame) cancelAnimationFrame(frame)
})
</script>

<template>
  <main ref="landingRoot" class="landing-page">
    <nav
      class="landing-nav"
      :class="{ 'landing-nav--scrolled': navScrolled }"
      aria-label="主导航"
    >
      <button
        class="brand"
        type="button"
        aria-label="回到首页"
        @click="scrollTo('hero')"
      >
        <span class="brand__mark">♪</span>
        <span>Vibe Music</span>
      </button>
      <div class="nav-links">
        <button
          v-for="item in navItems"
          :key="item.target"
          type="button"
          @click="scrollTo(item.target)"
        >
          {{ item.label }}
        </button>
      </div>
      <button class="nav-cta" type="button" @click="enter">
        立即体验 <span>↗</span>
      </button>
    </nav>

    <section id="hero" class="hero section-shell">
      <div class="hero__aurora hero__aurora--violet" aria-hidden="true"></div>
      <div class="hero__aurora hero__aurora--blue" aria-hidden="true"></div>
      <span class="note note--one" aria-hidden="true">♪</span
      ><span class="note note--two" aria-hidden="true">♫</span
      ><span class="note note--three" aria-hidden="true">·</span>
      <div class="hero__copy">
        <div class="eyebrow hero__eyebrow">
          <span class="eyebrow__dot"></span> AI POWERED MUSIC EXPERIENCE
        </div>
        <h1>Vibe Music<span class="hero__title-accent">.</span></h1>
        <p class="hero__lead">
          让 AI 帮你发现<br class="desktop-break" />更好的音乐
        </p>
        <p class="hero__description">
          不止是推荐。Vibe Music
          通过自然语言理解你的每一种心情、场景与灵感，帮你遇见下一首会喜欢的歌。
        </p>
        <div class="hero__actions">
          <button class="button button--primary" type="button" @click="enter">
            立即体验 <span>→</span>
          </button>
          <button
            class="button button--ghost"
            type="button"
            @click="scrollTo('features')"
          >
            了解更多 <span>↓</span>
          </button>
        </div>
        <div class="hero__meta">
          <span class="avatar-stack"><i></i><i></i><i></i></span
          ><span>与 10,000+ 位音乐爱好者一起探索</span>
        </div>
      </div>

      <div
        ref="visual"
        class="hero-visual"
        @pointermove="handlePointer"
        @pointerleave="resetPointer"
      >
        <div class="visual-shadow" aria-hidden="true"></div>
        <div class="music-window">
          <div class="window-topbar">
            <span class="window-dots"><i></i><i></i><i></i></span
            ><span class="window-title">VIBE MUSIC</span
            ><span class="window-action">•••</span>
          </div>
          <div class="music-window__body">
            <aside class="mock-sidebar">
              <div class="mock-brand"><span>♪</span> vibe</div>
              <div class="mock-nav mock-nav--active">◫ <span>首页</span></div>
              <div class="mock-nav">⌕ <span>发现</span></div>
              <div class="mock-nav">♡ <span>我的音乐</span></div>
              <div class="mock-nav">＋ <span>创建歌单</span></div>
              <div class="mock-sidebar__label">你的歌单</div>
              <div class="mock-playlist">
                <i></i><span>Late Night Focus</span>
              </div>
              <div class="mock-playlist">
                <i></i><span>Sunday Morning</span>
              </div>
            </aside>
            <div class="mock-content">
              <div class="mock-search">
                <span>⌕</span> 搜索你想听的音乐 <kbd>⌘ K</kbd>
              </div>
              <div class="mock-hero-card">
                <div>
                  <small>为今天的你</small
                  ><strong>Find your<br /><em>next vibe.</em></strong
                  ><span>基于你的聆听习惯，为你挑选</span>
                </div>
                <div class="mock-orbit">
                  <div class="orbit-ring"></div>
                  <div class="orbit-disc">♪</div>
                </div>
              </div>
              <div class="mock-section-head">
                <strong>为你推荐</strong><span>查看全部 →</span>
              </div>
              <div class="track-grid">
                <div
                  v-for="(track, index) in [
                    'Midnight City',
                    'Space Song',
                    'Afterglow',
                    'Bloom',
                  ]"
                  :key="track"
                  class="track-card"
                >
                  <div class="track-cover" :class="`track-cover--${index + 1}`">
                    <span>{{ ['M', 'S', 'A', 'B'][index] }}</span>
                  </div>
                  <strong>{{ track }}</strong
                  ><small>{{
                    ['M83', 'Beach House', 'Ed Sheeran', 'Odesza'][index]
                  }}</small>
                </div>
              </div>
              <div class="mock-player">
                <button
                  type="button"
                  :aria-label="isPlaying ? '暂停播放' : '开始播放'"
                  @click="togglePlaying"
                >
                  {{ isPlaying ? 'Ⅱ' : '▶' }}
                </button>
                <div class="player-track">
                  <strong>Midnight City</strong><span>M83</span>
                </div>
                <div class="player-progress">
                  <i :style="{ width: `${progress}%` }"></i>
                </div>
                <span class="player-time">1:42 / 4:03</span
                ><span class="player-volume">⌁</span>
              </div>
            </div>
          </div>
        </div>
        <div class="ai-chat">
          <div class="ai-chat__head">
            <span class="ai-avatar">✦</span>
            <div>
              <strong>AI 音乐助手</strong><span>在线 · 随时为你推荐</span>
            </div>
            <span class="ai-chat__more">•••</span>
          </div>
          <div class="ai-chat__messages">
            <div class="chat-bubble chat-bubble--user">
              给我推荐一些适合<br />深夜学习的音乐
            </div>
            <div class="chat-bubble chat-bubble--ai">
              当然可以，我为你找到了一些<br />适合专注学习的音乐 <span>✦</span>
            </div>
            <small class="recommend-label">为你推荐</small>
            <div class="recommend-item">
              <span class="recommend-cover recommend-cover--one">R</span
              ><span
                ><strong>River Flows in You</strong
                ><small>Yiruma · Piano</small></span
              ><button type="button" aria-label="播放 River Flows in You">
                ▶
              </button>
            </div>
            <div class="recommend-item">
              <span class="recommend-cover recommend-cover--two">C</span
              ><span
                ><strong>Clair de Lune</strong
                ><small>Debussy · Classical</small></span
              ><button type="button" aria-label="播放 Clair de Lune">▶</button>
            </div>
          </div>
          <div class="ai-chat__input">描述你的心情或场景 <span>↗</span></div>
        </div>
      </div>
    </section>

    <section id="features" class="features section-shell section-pad">
      <div class="section-intro" data-reveal>
        <div class="eyebrow">ONE PLATFORM, EVERY MOOD</div>
        <h2>全方位的<br /><em>音乐体验</em></h2>
        <p>
          从发现到播放，从搜索到创作，<br />Vibe Music 为你提供完整的音乐体验。
        </p>
      </div>
      <div class="feature-grid">
        <article
          v-for="(card, index) in featureCards"
          :key="card.label"
          class="feature-card"
          :class="`feature-card--${card.accent}`"
          data-reveal
          :style="{ '--delay': `${index * 90}ms` }"
        >
          <div class="feature-card__top">
            <span class="feature-icon">{{ card.icon }}</span
            ><span class="feature-number">0{{ index + 1 }}</span>
          </div>
          <span class="feature-label">{{ card.label }}</span>
          <h3>{{ card.title }}</h3>
          <p>{{ card.copy }}</p>
          <span class="feature-arrow">↗</span>
        </article>
      </div>
    </section>

    <section id="workflow" class="workflow section-shell section-pad">
      <div class="workflow__heading" data-reveal>
        <div>
          <div class="eyebrow">BEYOND CHAT</div>
          <h2>大模型 <em>+</em> 工具调用</h2>
        </div>
        <p>
          AI 不只是聊天。Vibe Music
          让大模型与业务系统协同工作，真正理解音乐数据并执行搜索。
        </p>
      </div>
      <div class="workflow-line" data-reveal>
        <div class="workflow-line__track"></div>
        <div
          v-for="(node, index) in workflowNodes"
          :key="node.step"
          class="workflow-node"
          :style="{ '--delay': `${index * 100}ms` }"
        >
          <div class="workflow-node__head">
            <span>{{ node.step }}</span
            ><i>{{ node.icon }}</i>
          </div>
          <strong>{{ node.title }}</strong
          ><em>{{ node.detail }}</em>
          <p>{{ node.body }}</p>
          <span v-if="index < workflowNodes.length - 1" class="workflow-arrow"
            >→</span
          >
        </div>
      </div>
      <div class="workflow-note" data-reveal>
        <span>LIVE DATA FLOW</span><i></i><i></i><i></i>
        <p>每一次回答，都来自真实的音乐业务能力</p>
      </div>
    </section>

    <section id="technology" class="technology section-shell section-pad">
      <div class="technology__copy" data-reveal>
        <div class="eyebrow">BUILT FOR YOUR VIBE</div>
        <h2>稳定可靠的<br /><em>技术架构</em></h2>
        <p>基于成熟的后端技术栈，构建高性能、可扩展的 AI 音乐平台。</p>
        <div class="tech-signal">
          <span></span><span></span><span></span
          ><small>系统运行稳定 · 持续进化中</small>
        </div>
      </div>
      <div class="tech-stack" data-reveal>
        <div
          v-for="(tech, index) in techStack"
          :key="tech"
          class="tech-chip"
          :style="{ '--delay': `${index * 60}ms` }"
        >
          <span>{{ ['SB', 'MP', 'R', 'SQL', 'M', 'LC', 'Q'][index] }}</span
          >{{ tech }}
        </div>
        <div class="tech-stack__orb" aria-hidden="true">
          <div></div>
          <span>VIBE<br />CORE</span>
        </div>
      </div>
    </section>

    <section id="cta" class="cta section-shell section-pad">
      <div class="cta__aurora" aria-hidden="true"></div>
      <div class="cta__content" data-reveal>
        <div class="eyebrow">YOUR NEXT SONG IS WAITING</div>
        <h2>准备好开启<br /><em>你的音乐之旅了吗？</em></h2>
        <p>立即体验 Vibe Music，和 AI 一起发现更多好音乐。</p>
        <button
          class="button button--primary button--large"
          type="button"
          @click="enter"
        >
          立即体验 Vibe Music <span>↗</span>
        </button>
      </div>
      <div class="cta-wave" aria-hidden="true">
        <i
          v-for="n in 38"
          :key="n"
          :style="{ height: `${16 + Math.sin(n * 0.68) * 13 + (n % 4) * 7}px` }"
        ></i>
      </div>
    </section>

    <footer class="landing-footer section-shell">
      <div class="footer-brand">
        <span class="brand__mark">♪</span>
        <div><strong>Vibe Music</strong><small>AI 让音乐更懂你</small></div>
      </div>
      <div class="footer-links">
        <button
          v-for="item in navItems.slice(1)"
          :key="item.target"
          type="button"
          @click="scrollTo(item.target)"
        >
          {{ item.label }}
        </button>
      </div>
      <span class="footer-copy">© 2026 Vibe Music</span>
    </footer>
  </main>
</template>

<style scoped>
@import url('https://fonts.googleapis.com/css2?family=DM+Mono:wght@400;500&family=Manrope:wght@400;500;600;700;800&display=swap');

:global(html) {
  scroll-behavior: smooth;
}
:global(body) {
  margin: 0;
  overflow-x: hidden;
}

.landing-page {
  --bg: #060914;
  --surface: rgba(18, 24, 46, 0.66);
  --line: rgba(163, 177, 221, 0.18);
  --text: #f7f8ff;
  --muted: #9ca8c4;
  --purple: #9b7bff;
  --cyan: #6ce5ff;
  position: relative;
  overflow: hidden;
  color: var(--text);
  background: var(--bg);
  font-family: Manrope, Inter, sans-serif;
}
.landing-page::before {
  content: '';
  position: fixed;
  z-index: 0;
  inset: 0;
  pointer-events: none;
  opacity: 0.17;
  background-image: radial-gradient(
    rgba(255, 255, 255, 0.55) 0.5px,
    transparent 0.5px
  );
  background-size: 4px 4px;
  mask-image: linear-gradient(to bottom, black, transparent 70%);
}
.section-shell {
  position: relative;
  z-index: 1;
  width: min(1180px, calc(100% - 64px));
  margin: 0 auto;
}
.section-pad {
  padding: 150px 0;
}
.landing-nav {
  position: fixed;
  z-index: 20;
  top: 0;
  left: 0;
  right: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: min(1180px, calc(100% - 64px));
  height: 74px;
  margin: 0 auto;
  transition: 0.3s ease;
}
.landing-nav::before {
  content: '';
  position: absolute;
  z-index: -1;
  inset: 0 -40px;
  opacity: 0;
  border-bottom: 1px solid transparent;
  background: rgba(6, 9, 20, 0.72);
  backdrop-filter: blur(18px);
  transition: 0.3s ease;
}
.landing-nav--scrolled::before {
  opacity: 1;
  border-color: var(--line);
}
.brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  border: 0;
  background: transparent;
  color: var(--text);
  cursor: pointer;
  font: inherit;
  font-weight: 800;
  font-size: 16px;
}
.brand__mark {
  display: grid;
  place-items: center;
  width: 31px;
  height: 31px;
  border-radius: 10px;
  color: white;
  background: linear-gradient(140deg, #8c6fff, #2f91fc);
  box-shadow: 0 5px 20px rgba(117, 88, 255, 0.36);
  font-size: 21px;
}
.nav-links {
  display: flex;
  gap: 30px;
  margin-left: 90px;
}
.nav-links button,
.footer-links button {
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  transition: color 0.2s ease;
}
.nav-links button:hover,
.footer-links button:hover {
  color: white;
}
.nav-cta {
  border: 1px solid rgba(145, 125, 255, 0.4);
  border-radius: 8px;
  padding: 10px 15px;
  color: white;
  background: rgba(110, 91, 220, 0.16);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  transition: 0.2s ease;
}
.nav-cta:hover {
  border-color: var(--purple);
  background: rgba(133, 106, 255, 0.3);
  box-shadow: 0 0 24px rgba(128, 96, 255, 0.2);
}
.hero {
  display: grid;
  grid-template-columns: minmax(0, 0.86fr) minmax(520px, 1.14fr);
  align-items: center;
  min-height: 800px;
  padding-top: 110px;
}
.hero__aurora {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
  filter: blur(12px);
  animation: breathe 11s ease-in-out infinite alternate;
}
.hero__aurora--violet {
  width: 660px;
  height: 660px;
  top: 30px;
  right: 4%;
  background: radial-gradient(circle, rgba(82, 58, 203, 0.38), transparent 68%);
}
.hero__aurora--blue {
  width: 500px;
  height: 500px;
  top: 300px;
  left: -20%;
  background: radial-gradient(
    circle,
    rgba(16, 146, 211, 0.16),
    transparent 70%
  );
  animation-delay: -4s;
}
.hero__copy {
  position: relative;
  z-index: 2;
  padding: 50px 0 0;
}
.eyebrow {
  color: #8d9bff;
  font-family: 'DM Mono', monospace;
  font-size: 10px;
  letter-spacing: 0.18em;
  line-height: 1.5;
}
.hero__eyebrow {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #adb5ff;
}
.eyebrow__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #b59aff;
  box-shadow: 0 0 12px #9c7aff;
}
h1,
h2,
h3,
p {
  margin-top: 0;
}
.hero h1 {
  margin: 24px 0 8px;
  font-size: clamp(58px, 7vw, 96px);
  font-weight: 800;
  letter-spacing: -0.07em;
  line-height: 0.98;
}
.hero__title-accent {
  color: #9879ff;
}
.hero__lead {
  margin-bottom: 20px;
  font-size: clamp(27px, 3.2vw, 42px);
  font-weight: 600;
  letter-spacing: -0.05em;
  line-height: 1.14;
}
.hero__description {
  max-width: 410px;
  color: var(--muted);
  font-size: 14px;
  line-height: 1.85;
}
.hero__actions {
  display: flex;
  gap: 12px;
  margin-top: 32px;
}
.button {
  display: inline-flex;
  align-items: center;
  gap: 14px;
  min-height: 45px;
  border: 1px solid transparent;
  border-radius: 8px;
  padding: 0 19px;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  transition: 0.25s ease;
}
.button span {
  font-size: 17px;
  line-height: 1;
}
.button--primary {
  color: white;
  background: linear-gradient(100deg, #8060ef, #4b87ef);
  box-shadow: 0 12px 30px rgba(93, 81, 226, 0.28);
}
.button--primary:hover {
  transform: translateY(-3px);
  filter: brightness(1.12);
  box-shadow: 0 17px 35px rgba(98, 90, 255, 0.42);
}
.button--ghost {
  border-color: var(--line);
  color: #ccd4ed;
  background: rgba(25, 32, 59, 0.4);
}
.button--ghost:hover {
  border-color: rgba(157, 135, 255, 0.55);
  color: white;
  background: rgba(32, 37, 74, 0.68);
}
.hero__meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 30px;
  color: #74809e;
  font-size: 10px;
}
.avatar-stack {
  display: flex;
  padding-left: 4px;
}
.avatar-stack i {
  width: 17px;
  height: 17px;
  margin-left: -4px;
  border: 2px solid var(--bg);
  border-radius: 50%;
  background: linear-gradient(130deg, #b4a1ff, #5542a4);
}
.avatar-stack i:nth-child(2) {
  background: linear-gradient(130deg, #8ee6ed, #3479ae);
}
.avatar-stack i:nth-child(3) {
  background: linear-gradient(130deg, #ffe0aa, #cc795f);
}
.note {
  position: absolute;
  z-index: 1;
  color: #8e7ee8;
  font-size: 25px;
  opacity: 0.34;
  animation: float 7s ease-in-out infinite;
}
.note--one {
  top: 26%;
  left: -2%;
}
.note--two {
  top: 65%;
  right: 45%;
  animation-delay: -3s;
  font-size: 18px;
}
.note--three {
  bottom: 12%;
  left: 42%;
  animation-delay: -1s;
}
.hero-visual {
  position: relative;
  z-index: 2;
  height: 555px;
  perspective: 1200px;
  transform: translateY(16px);
}
.music-window {
  position: absolute;
  top: 25px;
  left: 12px;
  width: 83%;
  min-width: 465px;
  height: 460px;
  overflow: hidden;
  border: 1px solid rgba(173, 184, 234, 0.28);
  border-radius: 13px;
  background: rgba(20, 27, 52, 0.82);
  box-shadow:
    0 40px 100px rgba(0, 0, 0, 0.4),
    0 0 50px rgba(87, 84, 255, 0.12);
  transform: rotateX(var(--tilt-x, 0deg)) rotateY(var(--tilt-y, 0deg))
    translateY(0);
  transition: transform 0.7s cubic-bezier(0.2, 0.7, 0.2, 1);
  animation: windowFloat 8s ease-in-out infinite;
  backdrop-filter: blur(16px);
}
.window-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 39px;
  border-bottom: 1px solid rgba(166, 179, 223, 0.14);
  padding: 0 14px;
  color: #8995b3;
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: 0.14em;
}
.window-dots {
  display: flex;
  gap: 4px;
}
.window-dots i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #506184;
}
.window-dots i:first-child {
  background: #8972e8;
}
.window-action {
  letter-spacing: 0.2em;
}
.music-window__body {
  display: flex;
  height: calc(100% - 39px);
}
.mock-sidebar {
  width: 125px;
  flex: none;
  border-right: 1px solid rgba(166, 179, 223, 0.11);
  padding: 20px 13px;
  color: #76829f;
  font-size: 9px;
}
.mock-brand {
  margin-bottom: 27px;
  color: #e4e9ff;
  font-size: 12px;
  font-weight: 800;
}
.mock-brand span {
  margin-right: 6px;
  color: #a18dff;
  font-size: 18px;
}
.mock-nav {
  display: flex;
  gap: 9px;
  align-items: center;
  padding: 9px;
  border-radius: 6px;
  margin-bottom: 4px;
}
.mock-nav--active {
  color: #d7d1ff;
  background: linear-gradient(90deg, rgba(123, 100, 239, 0.32), transparent);
}
.mock-sidebar__label {
  margin: 28px 9px 12px;
  color: #505c79;
  font-family: 'DM Mono', monospace;
  font-size: 7px;
  letter-spacing: 0.12em;
}
.mock-playlist {
  display: flex;
  gap: 6px;
  align-items: center;
  margin: 11px 9px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 8px;
}
.mock-playlist i {
  width: 12px;
  height: 12px;
  flex: none;
  border-radius: 3px;
  background: linear-gradient(130deg, #a887ff, #272e70);
}
.mock-playlist:nth-of-type(2) i {
  background: linear-gradient(130deg, #68d6ce, #274d77);
}
.mock-content {
  position: relative;
  flex: 1;
  padding: 20px 20px 0;
}
.mock-search {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 27px;
  border: 1px solid rgba(159, 171, 217, 0.13);
  border-radius: 6px;
  padding: 0 10px;
  color: #7f8eae;
  background: rgba(7, 12, 30, 0.36);
  font-size: 8px;
}
.mock-search span {
  color: #9ca6ca;
  font-size: 14px;
}
.mock-search kbd {
  margin-left: auto;
  border: 1px solid rgba(180, 189, 225, 0.15);
  border-radius: 3px;
  padding: 2px 4px;
  color: #687491;
  font: inherit;
  font-size: 7px;
}
.mock-hero-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 134px;
  overflow: hidden;
  border-radius: 8px;
  margin-top: 16px;
  padding: 0 24px;
  background: linear-gradient(110deg, #2b296c, #5a4cbd 55%, #202a5c);
}
.mock-hero-card small {
  display: block;
  margin-bottom: 6px;
  color: #c9c8eb;
  font-size: 8px;
}
.mock-hero-card strong {
  display: block;
  color: #fff;
  font-size: 21px;
  letter-spacing: -0.06em;
  line-height: 1.03;
}
.mock-hero-card em {
  color: #bcaaff;
  font-style: normal;
}
.mock-hero-card span {
  display: block;
  margin-top: 10px;
  color: #d0cbed;
  font-size: 7px;
}
.mock-orbit {
  position: relative;
  width: 112px;
  height: 112px;
}
.orbit-ring {
  position: absolute;
  inset: 4px;
  border: 1px solid rgba(214, 203, 255, 0.25);
  border-radius: 50%;
  transform: rotate(26deg) scaleX(0.52);
  box-shadow: 0 0 25px rgba(201, 188, 255, 0.22);
}
.orbit-disc {
  display: grid;
  place-items: center;
  position: absolute;
  inset: 21px;
  border-radius: 50%;
  color: #fff;
  background: radial-gradient(
    circle at 35% 26%,
    #d8ceff,
    #8270de 35%,
    #282456 67%,
    #151636 70%
  );
  box-shadow: 8px 10px 22px rgba(9, 7, 28, 0.4);
  font-size: 30px;
}
.mock-section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 18px 0 11px;
}
.mock-section-head strong {
  font-size: 11px;
}
.mock-section-head span {
  color: #8490ae;
  font-size: 7px;
}
.track-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}
.track-card {
  min-width: 0;
}
.track-cover {
  display: grid;
  place-items: center;
  aspect-ratio: 1;
  overflow: hidden;
  border-radius: 6px;
  color: rgba(255, 255, 255, 0.7);
  background: linear-gradient(145deg, #8872dc, #272744);
  font-size: 25px;
  font-weight: 800;
}
.track-cover--2 {
  background: linear-gradient(145deg, #6c9dbd, #172347);
}
.track-cover--3 {
  background: linear-gradient(145deg, #d08688, #392351);
}
.track-cover--4 {
  background: linear-gradient(145deg, #c8a15c, #1e4665);
}
.track-card strong,
.track-card small {
  display: block;
  overflow: hidden;
  margin-top: 7px;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.track-card strong {
  color: #dce2f5;
  font-size: 8px;
}
.track-card small {
  margin-top: 3px;
  color: #77839d;
  font-size: 7px;
}
.mock-player {
  display: flex;
  align-items: center;
  gap: 8px;
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 42px;
  border-top: 1px solid rgba(167, 180, 227, 0.16);
  padding: 0 15px;
  background: rgba(9, 14, 31, 0.85);
}
.mock-player button {
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  border: 0;
  border-radius: 50%;
  color: #fff;
  background: #876df6;
  cursor: pointer;
  font-size: 8px;
}
.player-track {
  display: flex;
  flex-direction: column;
  width: 88px;
}
.player-track strong {
  font-size: 8px;
}
.player-track span,
.player-time {
  color: #697694;
  font-size: 7px;
}
.player-progress {
  flex: 1;
  height: 2px;
  border-radius: 4px;
  background: #36405f;
}
.player-progress i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: #9c87ff;
  animation: progress 6s linear infinite alternate;
}
.player-volume {
  color: #7581a0;
  font-size: 15px;
}
.ai-chat {
  position: absolute;
  z-index: 3;
  top: 108px;
  right: -13px;
  width: 218px;
  overflow: hidden;
  border: 1px solid rgba(175, 186, 249, 0.35);
  border-radius: 13px;
  background: rgba(16, 23, 48, 0.88);
  box-shadow:
    0 26px 70px rgba(2, 4, 18, 0.5),
    0 0 38px rgba(109, 90, 244, 0.2);
  animation: chatFloat 7s ease-in-out infinite reverse;
  backdrop-filter: blur(20px);
}
.ai-chat__head {
  display: flex;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid rgba(166, 179, 223, 0.13);
  padding: 12px;
}
.ai-avatar {
  display: grid;
  place-items: center;
  width: 23px;
  height: 23px;
  border-radius: 7px;
  color: #fff;
  background: linear-gradient(135deg, #9274ff, #4b7fe3);
  font-size: 13px;
}
.ai-chat__head strong,
.ai-chat__head span {
  display: block;
}
.ai-chat__head strong {
  font-size: 9px;
}
.ai-chat__head div span {
  margin-top: 2px;
  color: #74809e;
  font-size: 7px;
}
.ai-chat__more {
  margin-left: auto;
  color: #74809e;
  font-size: 10px;
  letter-spacing: 0.12em;
}
.ai-chat__messages {
  padding: 13px 12px 10px;
}
.chat-bubble {
  width: fit-content;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 8px;
  line-height: 1.55;
}
.chat-bubble--user {
  margin-left: auto;
  color: #ded8ff;
  background: rgba(112, 91, 223, 0.35);
}
.chat-bubble--ai {
  margin-top: 9px;
  color: #b5c0dc;
  background: rgba(112, 128, 167, 0.15);
}
.chat-bubble--ai span {
  color: #a692ff;
}
.recommend-label {
  display: block;
  margin: 12px 0 7px;
  color: #6d7895;
  font-size: 7px;
}
.recommend-item {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 7px;
}
.recommend-cover {
  display: grid;
  place-items: center;
  width: 27px;
  height: 27px;
  border-radius: 5px;
  color: rgba(255, 255, 255, 0.8);
  background: linear-gradient(135deg, #bd937d, #39487e);
  font-size: 11px;
  font-weight: 700;
}
.recommend-cover--two {
  background: linear-gradient(135deg, #93aac8, #313a72);
}
.recommend-item > span:nth-child(2) {
  min-width: 0;
}
.recommend-item strong,
.recommend-item small {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.recommend-item strong {
  color: #e3e7f6;
  font-size: 8px;
}
.recommend-item small {
  margin-top: 3px;
  color: #75819c;
  font-size: 7px;
}
.recommend-item button {
  margin-left: auto;
  border: 0;
  color: #9c89ff;
  background: transparent;
  cursor: pointer;
  font-size: 8px;
}
.ai-chat__input {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-top: 1px solid rgba(166, 179, 223, 0.13);
  padding: 11px 12px;
  color: #64708e;
  font-size: 8px;
}
.ai-chat__input span {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  border-radius: 5px;
  color: white;
  background: #7963df;
  font-size: 12px;
}
.section-intro {
  display: flex;
  justify-content: space-between;
  align-items: end;
  margin-bottom: 48px;
}
.section-intro h2,
.workflow h2,
.technology h2,
.cta h2 {
  margin: 15px 0 0;
  font-size: clamp(38px, 5vw, 63px);
  letter-spacing: -0.065em;
  line-height: 1.03;
}
.section-intro h2 em,
.workflow h2 em,
.technology h2 em,
.cta h2 em {
  color: #9f87ff;
  font-style: normal;
}
.section-intro p {
  margin: 0 9% 2px 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.7;
}
.feature-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 13px;
}
.feature-card {
  position: relative;
  min-height: 250px;
  border: 1px solid rgba(158, 174, 224, 0.16);
  border-radius: 13px;
  padding: 20px;
  background: linear-gradient(
    145deg,
    rgba(24, 31, 58, 0.75),
    rgba(13, 18, 36, 0.64)
  );
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.035);
  transition:
    transform 0.28s ease,
    border-color 0.28s ease,
    box-shadow 0.28s ease;
}
.feature-card:hover {
  transform: translateY(-7px);
  border-color: rgba(154, 130, 255, 0.54);
  box-shadow:
    0 17px 35px rgba(3, 6, 20, 0.35),
    0 0 26px rgba(113, 83, 255, 0.12);
}
.feature-card__top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.feature-icon {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 10px;
  color: #bdadff;
  background: rgba(117, 92, 238, 0.15);
  font-size: 20px;
  transition: transform 0.3s ease;
}
.feature-card:hover .feature-icon {
  transform: scale(1.08) rotate(-5deg);
}
.feature-card--cyan .feature-icon {
  color: #8be8ee;
  background: rgba(76, 197, 219, 0.14);
}
.feature-card--blue .feature-icon {
  color: #89bdff;
  background: rgba(72, 134, 247, 0.14);
}
.feature-card--pink .feature-icon {
  color: #f3a8d5;
  background: rgba(218, 94, 177, 0.14);
}
.feature-number {
  color: #526080;
  font:
    10px 'DM Mono',
    monospace;
}
.feature-label {
  display: block;
  margin-top: 28px;
  color: #8c99bb;
  font:
    10px 'DM Mono',
    monospace;
  letter-spacing: 0.04em;
}
.feature-card h3 {
  margin: 9px 0 10px;
  font-size: 17px;
  letter-spacing: -0.04em;
}
.feature-card p {
  max-width: 220px;
  color: #8995b0;
  font-size: 11px;
  line-height: 1.7;
}
.feature-arrow {
  position: absolute;
  right: 20px;
  bottom: 18px;
  color: #a38dff;
  font-size: 17px;
}
.workflow {
  overflow: hidden;
}
.workflow__heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
}
.workflow__heading p {
  max-width: 350px;
  margin-bottom: 5px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.7;
}
.workflow-line {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 14px;
  position: relative;
  margin-top: 70px;
}
.workflow-line__track {
  position: absolute;
  top: 32px;
  left: 5%;
  right: 5%;
  height: 1px;
  background: linear-gradient(
    90deg,
    rgba(138, 112, 255, 0),
    rgba(138, 112, 255, 0.7),
    rgba(112, 224, 245, 0.7),
    rgba(138, 112, 255, 0)
  );
  background-size: 200% 100%;
  animation: dataFlow 4s linear infinite;
}
.workflow-node {
  position: relative;
  z-index: 1;
  min-height: 180px;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 15px;
  background: rgba(17, 23, 44, 0.76);
}
.workflow-node__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 26px;
  color: #6c7796;
  font:
    9px 'DM Mono',
    monospace;
}
.workflow-node__head i {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 1px solid rgba(148, 128, 255, 0.36);
  border-radius: 8px;
  color: #b09cff;
  background: rgba(119, 97, 230, 0.16);
  font-size: 14px;
  font-style: normal;
}
.workflow-node strong,
.workflow-node em {
  display: block;
}
.workflow-node strong {
  font-size: 14px;
}
.workflow-node em {
  margin-top: 4px;
  color: #a28cff;
  font:
    10px 'DM Mono',
    monospace;
  font-style: normal;
}
.workflow-node p {
  margin: 18px 0 0;
  color: #7d89a6;
  font-size: 10px;
  line-height: 1.6;
}
.workflow-arrow {
  position: absolute;
  z-index: 2;
  top: 25px;
  right: -17px;
  color: #9181ef;
  font-size: 18px;
  text-shadow: 0 0 12px #6c5bd5;
  animation: arrowPulse 1.8s ease-in-out infinite;
}
.workflow-note {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-top: 26px;
  color: #687594;
  font:
    9px 'DM Mono',
    monospace;
}
.workflow-note > span {
  color: #76dfe4;
  letter-spacing: 0.12em;
}
.workflow-note i {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #6de0e7;
  animation: dotPulse 1.2s ease-in-out infinite;
}
.workflow-note i:nth-of-type(2) {
  animation-delay: 0.25s;
}
.workflow-note i:nth-of-type(3) {
  animation-delay: 0.5s;
}
.workflow-note p {
  margin: 0 0 0 auto;
  font-family: Manrope, sans-serif;
  font-size: 11px;
}
.technology {
  display: grid;
  grid-template-columns: 0.8fr 1.2fr;
  align-items: center;
  gap: 90px;
}
.technology__copy p {
  max-width: 330px;
  margin-top: 25px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.8;
}
.tech-signal {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 32px;
  color: #72809d;
  font:
    9px 'DM Mono',
    monospace;
}
.tech-signal span {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #60d8dc;
  box-shadow: 0 0 10px #60d8dc;
}
.tech-signal small {
  margin-left: 7px;
  font-size: 9px;
}
.tech-stack {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  position: relative;
  padding: 15px;
}
.tech-chip {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 55px;
  border: 1px solid rgba(153, 168, 215, 0.16);
  border-radius: 9px;
  padding: 0 13px;
  color: #c4cee6;
  background: rgba(23, 30, 56, 0.7);
  font-size: 11px;
}
.tech-chip span {
  display: grid;
  place-items: center;
  width: 27px;
  height: 27px;
  border-radius: 7px;
  color: #aca2ff;
  background: rgba(120, 98, 227, 0.15);
  font:
    9px 'DM Mono',
    monospace;
}
.tech-chip:nth-child(2n) span {
  color: #8edadd;
  background: rgba(84, 188, 194, 0.13);
}
.tech-stack__orb {
  display: grid;
  place-items: center;
  position: absolute;
  right: 6%;
  bottom: -34px;
  width: 127px;
  height: 127px;
  border: 1px solid rgba(148, 132, 255, 0.35);
  border-radius: 50%;
  color: #b3a2ff;
  background: radial-gradient(
    circle,
    rgba(97, 79, 214, 0.32),
    rgba(19, 23, 55, 0.55) 65%,
    transparent 66%
  );
  box-shadow: 0 0 50px rgba(103, 82, 242, 0.18);
  font:
    10px 'DM Mono',
    monospace;
  text-align: center;
  letter-spacing: 0.12em;
}
.tech-stack__orb::before,
.tech-stack__orb::after {
  content: '';
  position: absolute;
  border: 1px solid rgba(140, 122, 255, 0.2);
  border-radius: 50%;
}
.tech-stack__orb::before {
  inset: 13px;
  transform: rotate(38deg) scaleX(0.45);
}
.tech-stack__orb::after {
  inset: -9px;
  transform: rotate(-32deg) scaleX(0.4);
}
.tech-stack__orb div {
  position: absolute;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #74e4e9;
  box-shadow: 0 0 13px #74e4e9;
  top: 16px;
  right: 20px;
}
.cta {
  min-height: 560px;
  display: grid;
  place-items: center;
  overflow: hidden;
  border-top: 1px solid rgba(132, 146, 207, 0.1);
  border-bottom: 1px solid rgba(132, 146, 207, 0.1);
  text-align: center;
}
.cta__aurora {
  position: absolute;
  width: 760px;
  height: 420px;
  border-radius: 50%;
  background: radial-gradient(
    ellipse,
    rgba(87, 65, 216, 0.31),
    transparent 66%
  );
  filter: blur(10px);
}
.cta__content {
  position: relative;
  z-index: 2;
}
.cta h2 {
  margin-top: 17px;
  font-size: clamp(42px, 6vw, 70px);
}
.cta p {
  margin: 23px 0 28px;
  color: var(--muted);
  font-size: 13px;
}
.button--large {
  min-height: 50px;
  padding: 0 24px;
}
.cta-wave {
  display: flex;
  align-items: end;
  justify-content: center;
  gap: 5px;
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 108px;
  opacity: 0.42;
  mask-image: linear-gradient(to top, black, transparent);
}
.cta-wave i {
  width: 2px;
  border-radius: 3px 3px 0 0;
  background: linear-gradient(to top, #6d57df, #69d7df);
  animation: wave 1.9s ease-in-out infinite alternate;
}
.cta-wave i:nth-child(3n) {
  animation-delay: -0.7s;
}
.cta-wave i:nth-child(4n) {
  animation-delay: -1.2s;
}
.landing-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 100px;
}
.footer-brand {
  display: flex;
  align-items: center;
  gap: 10px;
}
.footer-brand strong,
.footer-brand small {
  display: block;
}
.footer-brand strong {
  font-size: 13px;
}
.footer-brand small {
  margin-top: 4px;
  color: #71809d;
  font-size: 9px;
}
.footer-links {
  display: flex;
  gap: 25px;
}
.footer-copy {
  color: #596681;
  font:
    9px 'DM Mono',
    monospace;
}
[data-reveal] {
  opacity: 0;
  transform: translateY(28px);
  transition:
    opacity 0.7s ease,
    transform 0.7s cubic-bezier(0.2, 0.7, 0.2, 1);
  transition-delay: var(--delay, 0ms);
}
.is-visible[data-reveal] {
  opacity: 1;
  transform: translateY(0);
}
@keyframes breathe {
  from {
    transform: translate(0, 0) scale(1);
  }
  to {
    transform: translate(25px, -20px) scale(1.08);
  }
}
@keyframes float {
  0%,
  100% {
    transform: translateY(0) rotate(-5deg);
  }
  50% {
    transform: translateY(-20px) rotate(8deg);
  }
}
@keyframes windowFloat {
  0%,
  100% {
    transform: rotateX(var(--tilt-x, 0deg)) rotateY(var(--tilt-y, 0deg))
      translateY(0);
  }
  50% {
    transform: rotateX(var(--tilt-x, 0deg)) rotateY(var(--tilt-y, 0deg))
      translateY(-8px);
  }
}
@keyframes chatFloat {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-11px);
  }
}
@keyframes progress {
  from {
    width: 38%;
  }
  to {
    width: 62%;
  }
}
@keyframes dataFlow {
  from {
    background-position: 100% 0;
  }
  to {
    background-position: -100% 0;
  }
}
@keyframes arrowPulse {
  0%,
  100% {
    opacity: 0.35;
    transform: translateX(0);
  }
  50% {
    opacity: 1;
    transform: translateX(4px);
  }
}
@keyframes dotPulse {
  0%,
  100% {
    opacity: 0.25;
    transform: scale(0.8);
  }
  50% {
    opacity: 1;
    transform: scale(1.5);
  }
}
@keyframes wave {
  from {
    transform: scaleY(0.7);
  }
  to {
    transform: scaleY(1.15);
  }
}
@media (max-width: 1050px) {
  .hero {
    grid-template-columns: 0.9fr 1.1fr;
  }
  .hero-visual {
    transform: scale(0.87) translate(30px, 10px);
    transform-origin: center right;
  }
  .music-window {
    left: 0;
  }
  .ai-chat {
    right: -2px;
  }
  .nav-links {
    margin-left: 20px;
    gap: 18px;
  }
  .technology {
    gap: 45px;
  }
}
@media (max-width: 820px) {
  .section-shell {
    width: min(100% - 40px, 600px);
  }
  .landing-nav {
    width: calc(100% - 40px);
  }
  .nav-links {
    display: none;
  }
  .hero {
    display: block;
    min-height: auto;
    padding: 140px 0 50px;
  }
  .hero__copy {
    padding: 0;
  }
  .hero__description {
    max-width: 520px;
  }
  .hero-visual {
    width: 650px;
    max-width: 100%;
    height: 475px;
    margin: 50px auto 0;
    transform: scale(0.77);
    transform-origin: top left;
  }
  .music-window {
    width: 85%;
  }
  .ai-chat {
    right: -4px;
  }
  .section-pad {
    padding: 95px 0;
  }
  .section-intro,
  .workflow__heading {
    display: block;
  }
  .section-intro p,
  .workflow__heading p {
    margin-top: 22px;
  }
  .feature-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .workflow-line {
    grid-template-columns: repeat(5, minmax(150px, 1fr));
    width: 840px;
  }
  .workflow {
    overflow-x: auto;
  }
  .technology {
    grid-template-columns: 1fr;
  }
  .tech-stack {
    max-width: 620px;
  }
  .cta {
    min-height: 470px;
  }
  .landing-footer {
    flex-wrap: wrap;
    gap: 20px;
    padding: 28px 0;
  }
}
@media (max-width: 520px) {
  .section-shell {
    width: calc(100% - 36px);
  }
  .landing-nav {
    width: calc(100% - 36px);
  }
  .brand {
    font-size: 14px;
  }
  .nav-cta {
    padding: 9px 11px;
  }
  .hero h1 {
    font-size: 58px;
  }
  .hero__lead {
    font-size: 30px;
  }
  .desktop-break {
    display: none;
  }
  .hero__actions {
    flex-wrap: wrap;
  }
  .hero__meta {
    font-size: 9px;
  }
  .hero-visual {
    width: 610px;
    height: 410px;
    margin-top: 38px;
    transform: scale(0.59);
  }
  .music-window {
    left: 0;
  }
  .ai-chat {
    right: 0;
  }
  .feature-grid {
    grid-template-columns: 1fr;
  }
  .feature-card {
    min-height: 210px;
  }
  .workflow-line {
    margin-top: 45px;
  }
  .workflow-note {
    flex-wrap: wrap;
  }
  .workflow-note p {
    width: 100%;
    margin: 8px 0 0;
  }
  .tech-stack {
    grid-template-columns: repeat(2, 1fr);
    padding: 0;
  }
  .tech-stack__orb {
    right: -8px;
    bottom: -52px;
    transform: scale(0.75);
  }
  .cta h2 {
    font-size: 41px;
  }
  .footer-links {
    order: 3;
    width: 100%;
    justify-content: space-between;
    gap: 8px;
  }
  .footer-copy {
    margin-left: auto;
  }
}
@media (prefers-reduced-motion: reduce) {
  :global(html) {
    scroll-behavior: auto;
  }
  *,
  *::before,
  *::after {
    animation-duration: 0.001ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.001ms !important;
  }
  [data-reveal] {
    opacity: 1;
    transform: none;
  }
}
</style>
