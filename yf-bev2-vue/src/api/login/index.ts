import request from '@/config/axios'

// 新增：定义人脸识别登录请求参数类型（对应前端传递的参数，与DTO一致）
interface FaceLoginRequestParams {
  account: string
  password: string
  idCard: string
  faceImageBase64: string
}

/**
 * 原有普通登录接口
 */
export const apiLogin = (data: any) => {
  return request.post({
    url: '/api/sys/user/login',
    data
  })
}

/**
 * 用户注册接口
 */
export const apiRegister = (data: any) => {
  return request.post({
    url: '/api/sys/user/reg',
    data
  })
}

/**
 * 退出登录接口
 */
export const logoutApi = () => {
  return request.post({ url: '/api/sys/user/logout' })
}

/**
 * 路由获取接口
 */
export const routesApi = (data: any) => {
  return request.post({
    url: '/api/sys/menu/routes',
    data
  })
}

/**
 * 新增：人脸识别验证登录接口（对应后端新增的接口地址）
 * @param data 人脸识别登录请求参数（账号、密码、身份证、人脸Base64）
 */
export const apiFaceLogin = (data: FaceLoginRequestParams) => {
  return request.post({
    // 接口地址与后端LoginController中定义的保持一致（可根据后端实际调整）
    url: '/api/login/face-verify',
    data
  })
}