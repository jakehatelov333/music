import axios, {
  AxiosInstance,
  AxiosRequestConfig,
  InternalAxiosRequestConfig,
  AxiosRequestHeaders,
} from 'axios'
import NProgress from '@/config/nprogress'
import 'nprogress/nprogress.css'
import { UserStore } from '@/stores/modules/user'
import { ElMessage } from 'element-plus'
import { normalizeMediaUrls } from '@/utils/media'

const instance: AxiosInstance = axios.create({
  // 开发环境由 Vite 同源代理到后端；生产环境仍可通过环境变量指定绝对地址。
  baseURL: import.meta.env.DEV
    ? ''
    : import.meta.env.VITE_APP_BASE_API || '',
  timeout: 20000, // 设置超时时间 20秒
  headers: {
    Accept: 'application/json, text/plain, */*',
    'Content-Type': 'application/json',
    'X-Requested-With': 'XMLHttpRequest',
  },
  withCredentials: false,
})

// 请求拦截器
instance.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // 后台行为（播放上报等）用 X-Silent-Request 标记：不弹顶部进度条。
    // 否则每切一首歌都会闪一下进度条，而用户并没有在等任何东西。
    // 这个头只是给本拦截器看的，发出前会删掉，不进入实际请求。
    const silent = config.headers?.['X-Silent-Request'] === '1'
    if (silent) {
      delete config.headers['X-Silent-Request']
    } else {
      // 开启进度条
      NProgress.start()
    }

    // 只有登录请求不需要添加token
    if (config.url?.includes('/user/login')) {
      return config
    }

    // 从 pinia 中获取token
    const userStore = UserStore()
    const token = userStore.userInfo?.token

    if (token) {
      // 确保headers对象存在并且是正确的类型
      if (!config.headers) {
        config.headers = {} as AxiosRequestHeaders
      }
      // 添加Bearer前缀
      config.headers.Authorization = token
    }

    // console.log('请求URL:', config.url)
    // console.log('请求头:', config.headers)
    return config
  },
  (error) => {
    console.error('请求错误:', error)
    return Promise.reject(error)
  }
)

// 响应拦截器
instance.interceptors.response.use(
  (response) => {
    // 关闭进度条
    NProgress.done()
    const { data } = response
    return normalizeMediaUrls(data)
  },
  (error) => {
    // 关闭进度条
    NProgress.done()

    if (axios.isCancel(error)) return Promise.reject(error)

    // 静默请求（播放历史上报）失败时不弹全局错误提示：
    // 它不阻塞播放，用户也不该因为「记录失败」被打断。
    // 这里只记一条 warn，便于排查；具体错误由调用方自行决定是否展示。
    const silent =
      error.config?.headers?.['X-Silent-Request'] === '1' ||
      error.config?.url?.includes('/play-history/record')
    if (silent) {
      console.warn('后台请求失败（已忽略）:', error.config?.url, error.message)
      return Promise.reject(error)
    }

    if (error.response) {
      switch (error.response.status) {
        case 401:
          // 如果不是登录请求，则清除用户信息
          if (!error.config.url?.includes('/user/login')) {
            const userStore = UserStore()
            userStore.clearUserInfo()
            ElMessage.error('登录已过期，请重新登录')
          } else {
            ElMessage.error('邮箱或密码错误')
          }
          break
        case 403:
          ElMessage.error('没有权限')
          break
        case 404:
          ElMessage.error('请求的资源不存在')
          break
        case 409:
        case 410:
          ElMessage.error(
            error.response.data?.message || '歌单状态已变化，请刷新后重试'
          )
          break
        case 500:
          ElMessage.error('服务器错误')
          break
        default:
          ElMessage.error('网络错误')
      }
    } else {
      ElMessage.error('网络连接失败')
    }

    return Promise.reject(error)
  }
)

// 封装request方法
export const http = <T>(
  method: 'get' | 'post' | 'put' | 'delete' | 'patch',
  url: string,
  config?: Omit<AxiosRequestConfig, 'method' | 'url'>
): Promise<T> => {
  return instance({ method, url, ...config })
}

// 封装get方法
export const httpGet = <T>(url: string, params?: object): Promise<T> =>
  instance.get(url, { params })

// 封装post方法
export const httpPost = <T>(
  url: string,
  data?: object,
  header?: object
): Promise<T> => instance.post(url, data, { headers: header })

// 封装upload方法
export const httpUpload = <T>(
  url: string,
  formData: FormData,
  header?: object
): Promise<T> => {
  return instance.post(url, formData, {
    headers: {
      'Content-Type': 'multipart/form-data',
      ...header,
    },
  })
}
