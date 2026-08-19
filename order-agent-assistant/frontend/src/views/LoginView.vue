<script setup>
import { onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { showToast } from '../composables/useToast'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const mode = ref('login') // login | register
const loading = ref(false)
const form = reactive({ username: '', password: '', phone: '', code: '' })

const PHONE_RE = /^1[3-9]\d{9}$/ // 1 开头 + 11 位，与真实号段同规则
const smsCode = ref('') // 演示环境：前端生成的模拟验证码
const countdown = ref(0)
let codeTimer = null

/** 演示环境的「发送验证码」：真实系统走短信服务商，这里把一条模拟短信展示在页面上 */
function sendCode() {
  if (!PHONE_RE.test(form.phone)) {
    showToast('请输入正确的 11 位手机号', 'error')
    return
  }
  smsCode.value = String(Math.floor(1000 + Math.random() * 9000))
  countdown.value = 60
  showToast('验证码已发送', 'success')
  clearInterval(codeTimer)
  codeTimer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) clearInterval(codeTimer)
  }, 1000)
}

async function submit() {
  if (mode.value === 'register') {
    if (!form.username || !form.password || !form.phone || !form.code) {
      showToast('请填写完整信息', 'error')
      return
    }
    if (!PHONE_RE.test(form.phone)) {
      showToast('手机号格式不正确', 'error')
      return
    }
    if (form.code !== smsCode.value) {
      showToast('验证码不正确', 'error')
      return
    }
  } else if (!form.username || !form.password) {
    showToast('请填写完整信息', 'error')
    return
  }
  loading.value = true
  try {
    if (mode.value === 'register') {
      // 验证码是演示校验，不发给后端
      await auth.register({ username: form.username, password: form.password, phone: form.phone })
      showToast('注册成功，请登录', 'success')
      mode.value = 'login'
      form.password = ''
      form.code = ''
      smsCode.value = ''
    } else {
      await auth.login({ username: form.username, password: form.password })
      showToast(`欢迎回来，${auth.username}`, 'success')
      router.replace(route.query.redirect || '/')
    }
  } catch (e) {
    /* 拦截器已 toast */
  } finally {
    loading.value = false
  }
}

onUnmounted(() => clearInterval(codeTimer))
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="hero">
        <div class="hero-inner">
          <div class="hero-badge">AI Agent Demo</div>
          <h1>订单助手</h1>
          <p class="hero-sub">用自然语言管理你的订单，AI 帮你跑完全程。</p>
          <ul class="hero-points">
            <li><span>🗣️</span> 自然语言查单、取消订单</li>
            <li><span>💬</span> 多轮对话记忆，跨请求上下文</li>
            <li><span>🛡️</span> 写操作必须人工确认后才执行</li>
          </ul>
        </div>
      </div>

      <div class="form-side">
        <div class="tabs">
          <button :class="{ on: mode === 'login' }" @click="mode = 'login'">登录</button>
          <button :class="{ on: mode === 'register' }" @click="mode = 'register'">注册</button>
        </div>

        <form class="login-form" @submit.prevent="submit">
          <div class="field">
            <label class="label">用户名</label>
            <input v-model="form.username" class="input" placeholder="3-20 位字符" autocomplete="username" />
          </div>
          <div class="field">
            <label class="label">密码</label>
            <input v-model="form.password" class="input" type="password" placeholder="至少 6 位" autocomplete="current-password" />
          </div>
          <div v-if="mode === 'register'" class="field">
            <label class="label">手机号</label>
            <div class="phone-row">
              <input v-model="form.phone" class="input" placeholder="11 位手机号，用于接收退款通知" autocomplete="tel" />
              <button type="button" class="btn btn-ghost code-btn" :disabled="countdown > 0" @click="sendCode">
                {{ countdown > 0 ? `${countdown}s` : '获取验证码' }}
              </button>
            </div>
          </div>
          <div v-if="mode === 'register'" class="field">
            <label class="label">验证码</label>
            <input v-model="form.code" class="input" placeholder="请输入 4 位验证码" inputmode="numeric" maxlength="4" />
            <div v-if="smsCode" class="sms-bubble">
              📱 模拟短信 · 演示环境（正式系统由短信服务下发）：验证码 <b>{{ smsCode }}</b>，5 分钟内有效。
            </div>
          </div>

          <button type="submit" class="btn btn-primary btn-lg btn-block" :disabled="loading">
            {{ loading ? '请稍候…' : mode === 'login' ? '登 录' : '注 册' }}
          </button>
        </form>

        <p class="tip">
          注册即创建账号；已有账号直接登录。<br />
          演示管理员：<b class="mono">admin / admin123</b>
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background:
    radial-gradient(60rem 30rem at 110% -10%, rgba(139, 92, 246, 0.12), transparent 60%),
    radial-gradient(50rem 26rem at -20% 110%, rgba(99, 102, 241, 0.12), transparent 60%),
    var(--bg);
}
.login-card {
  display: flex;
  width: 880px;
  max-width: 100%;
  min-height: 520px;
  background: var(--surface);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
}
.hero {
  flex: 1;
  background: var(--grad);
  color: #fff;
  display: flex;
  align-items: center;
  padding: 48px 40px;
}
.hero-badge {
  display: inline-block;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 1px;
  text-transform: uppercase;
  padding: 4px 12px;
  border-radius: var(--r-full);
  background: rgba(255, 255, 255, 0.18);
  backdrop-filter: blur(4px);
}
.hero h1 {
  font-size: 34px;
  font-weight: 800;
  margin: 16px 0 10px;
  letter-spacing: 1px;
}
.hero-sub {
  font-size: 15px;
  opacity: 0.92;
  line-height: 1.6;
}
.hero-points {
  list-style: none;
  margin-top: 28px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.hero-points li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  opacity: 0.95;
}
.hero-points span {
  font-size: 18px;
}
.form-side {
  width: 380px;
  display: flex;
  flex-direction: column;
  padding: 40px 36px;
}
.tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 28px;
  background: var(--surface-2);
  border-radius: var(--r-md);
  padding: 4px;
}
.tabs button {
  flex: 1;
  padding: 8px;
  border-radius: var(--r-sm);
  font-size: 14px;
  font-weight: 600;
  color: var(--text-2);
  transition: background var(--dur) var(--ease), color var(--dur) var(--ease);
}
.tabs button.on {
  background: var(--surface);
  color: var(--primary);
  box-shadow: var(--shadow-sm);
}
.phone-row {
  display: flex;
  gap: 8px;
}
.phone-row .input {
  flex: 1;
}
.code-btn {
  white-space: nowrap;
  padding: 0 14px;
  font-size: 13px;
  color: var(--primary);
  border-color: var(--primary-soft);
  background: var(--primary-soft);
}
.code-btn:disabled {
  color: var(--text-3);
  background: var(--surface-2);
  border-color: var(--border);
}
.sms-bubble {
  margin-top: 8px;
  padding: 8px 12px;
  border-radius: var(--r-sm);
  background: var(--surface-2);
  border: 1px dashed var(--primary-soft);
  font-size: 12.5px;
  color: var(--text-2);
  line-height: 1.6;
}
.sms-bubble b {
  color: var(--primary);
  font-size: 14px;
  letter-spacing: 2px;
}
.tip {
  margin-top: 18px;
  text-align: center;
  font-size: 12.5px;
  color: var(--text-3);
}
</style>
