// @ts-ignore
// @ts-ignore
// @ts-ignore
// @ts-ignore
import type {
  InternalAxiosRequestConfig,
  AxiosResponse,
  AxiosRequestConfig,
  AxiosInstance,
  AxiosRequestHeaders,
  AxiosError
} from 'axios'

interface RequestInterceptors<T> {
  // 请求拦截
  requestInterceptors?: (config: InternalAxiosRequestConfig) => InternalAxiosRequestConfig
  requestInterceptorsCatch?: (err: any) => any
  // 响应拦截
  responseInterceptors?: (config: T) => T
  responseInterceptorsCatch?: (err: any) => any
}
interface AxiosConfig<T = AxiosResponse> {
  code: number
  defaultHeaders: AxiosHeaders
  timeout: number
  interceptors: RequestInterceptors<T>
}

interface RequestConfig<T = AxiosResponse> extends AxiosRequestConfig {
  interceptors?: RequestInterceptors<T>
}
interface IResponse<T = any> {
  code: number | string;
  data: T;
  // 新增：message 字段（可选，用 ? 标记，避免强制要求所有接口都返回 message）
  message?: string;
}

export default IResponse;
export {
  AxiosResponse,
  RequestInterceptors,
  RequestConfig,
  AxiosConfig,
  AxiosInstance,
  InternalAxiosRequestConfig,
  AxiosRequestHeaders,
  AxiosError
}
