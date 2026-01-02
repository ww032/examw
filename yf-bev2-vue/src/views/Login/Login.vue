<script setup lang="ts">
import { LoginForm, RegisterForm } from './components'
import { ThemeSwitch } from '@/components/ThemeSwitch'
import { LocaleDropdown } from '@/components/LocaleDropdown'
import { useI18n } from '@/hooks/web/useI18n'
import { useAppStore } from '@/store/modules/app'
import { useDesign } from '@/hooks/web/useDesign'
import { computed, ref } from 'vue'
import { ElScrollbar, ElMessage } from 'element-plus'
// 引入人脸验证弹窗组件
import LoginFaceModal from '@/components/LoginFaceModal.vue'
// 引入路由（如需跳转首页，项目原有路由实例，保持与项目一致）
import { useRouter } from 'vue-router'

const { getPrefixCls } = useDesign()
const prefixCls = getPrefixCls('login')

const appStore = useAppStore()
const { t } = useI18n()
// 路由实例（用于登录成功后跳转）
const router = useRouter()

const isLogin = ref(true)
// 1. 定义人脸弹窗组件ref，用于调用弹窗的openModal方法
const faceModalRef = ref<InstanceType<typeof LoginFaceModal>>()
// 2. 定义LoginForm组件ref，用于获取登录表单的账号、密码和校验状态
const loginFormRef = ref<InstanceType<typeof LoginForm>>()

const siteInfo = computed(() => appStore.getSiteInfo)

const toRegister = () => {
  isLogin.value = false
}

const toLogin = () => {
  isLogin.value = true
}

// 3. 新增：接收LoginForm的登录提交事件（需在LoginForm组件内触发该事件）
const handleLoginSubmit = async (loginFormData?: { account: string; password: string }) => {
  // 优先从传入参数获取表单数据，若未传入则从LoginForm实例中获取（兼容两种方式）
  let formData = loginFormData
  if (!formData && loginFormRef.value) {
    // 需确保LoginForm组件暴露了表单数据（后续会提示修改LoginForm）
    formData = (loginFormRef.value as any).loginForm
  }

  if (!formData || !formData.account || !formData.password) {
    ElMessage.warning(t('login.pleaseFillAccountPassword') || '请填写完整账号和密码')
    return
  }

  // 打开人脸验证弹窗，传递账号密码数据
  faceModalRef.value?.openModal(formData)
}

// 4. 新增：人脸验证成功后的回调处理（完成后续登录流程）
const handleFaceLoginSuccess = async () => {
  try {
    // 此处复用项目原有登录逻辑（如获取Token、存储用户信息）
    // 示例：调用原有登录接口、存储Token到localStorage/pinia
    // const loginRes = await originalLoginApi((loginFormRef.value as any).loginForm)
    // if (loginRes.code === 200) {
    //   localStorage.setItem('token', loginRes.data.token)
    // }

    ElMessage.success(t('login.loginSuccess') || '登录成功')
    // 跳转首页（与项目原有路由地址保持一致）
    await router.push('/dashboard')
  } catch (error) {
    ElMessage.error(t('login.loginFailed') || '登录流程异常，请重试')
    console.error('人脸验证成功后登录异常：', error)
  }
}

// 5. 新增：人脸弹窗关闭后的回调（可选，用于重置表单状态等）
const handleFaceModalClose = () => {
  console.log('人脸验证弹窗已关闭')
  // 可选：重置LoginForm表单校验状态
  if (loginFormRef.value) {
    ;(loginFormRef.value as any).clearValidate?.()
  }
}
</script>

<template>
  <div
      :class="prefixCls"
      class="h-[100%] relative lt-xl:bg-[var(--login-bg-color)] lt-sm:px-10px lt-xl:px-10px lt-md:px-10px"
  >
    <ElScrollbar class="h-full">
      <div class="relative flex mx-auto h-100vh">
        <div
            :class="`${prefixCls}__left flex-1 bg-gray-500 bg-opacity-20 relative p-30px lt-xl:hidden`"
        >
          <div class="flex items-center relative text-white">
            <img :src="siteInfo.loginLogo" alt="" class="w-48px h-48px mr-10px" />
            <span class="text-20px font-bold">{{ siteInfo.siteName }}</span>
          </div>
          <div class="flex justify-center items-center h-[calc(100%-60px)]">
            <TransitionGroup
                appear
                tag="div"
                enter-active-class="animate__animated animate__bounceInLeft"
            >
              <img :src="siteInfo.loginBg" key="1" alt="" class="w-350px" />
              <div class="text-3xl text-white" key="2" style="margin-top: 50px">{{
                  t('login.welcome')
                }}</div>
              <div class="mt-5 font-normal text-white text-14px" key="3">
                {{ t('login.message') }}
              </div>
            </TransitionGroup>
          </div>
        </div>
        <div class="flex-1 p-30px lt-sm:p-10px dark:bg-[var(--login-bg-color)] relative">
          <div
              class="flex justify-between items-center text-white at-2xl:justify-end at-xl:justify-end"
          >
            <div class="flex items-center at-2xl:hidden at-xl:hidden">
              <img :src="siteInfo.loginLogo" alt="" class="w-48px h-48px mr-10px" />
              <span class="text-20px font-bold">{{ siteInfo.siteName }}</span>
            </div>

            <div class="flex justify-end items-center space-x-10px">
              <ThemeSwitch />
              <LocaleDropdown class="lt-xl:text-white dark:text-white" />
            </div>
          </div>
          <Transition appear enter-active-class="animate__animated animate__bounceInRight">
            <div
                class="h-full flex items-center m-auto w-[100%] at-2xl:max-w-600px at-xl:max-w-600px at-md:max-w-600px at-lg:max-w-600px"
            >
              <!-- 给LoginForm添加ref，绑定登录提交事件 -->
              <LoginForm
                  v-if="isLogin"
                  ref="loginFormRef"
                  class="p-20px h-auto m-auto lt-xl:rounded-3xl lt-xl:light:bg-white"
                  @to-register="toRegister"
                  @submit="handleLoginSubmit" <!-- 新增：监听LoginForm的提交事件 -->
              />
              <RegisterForm
                  v-else
                  class="p-20px h-auto m-auto lt-xl:rounded-3xl lt-xl:light:bg-white"
                  @to-login="toLogin"
              />
            </div>
          </Transition>
        </div>
      </div>
    </ElScrollbar>

    <!-- 新增：引入人脸验证弹窗组件，绑定相关事件 -->
    <LoginFaceModal
        ref="faceModalRef"
        @success="handleFaceLoginSuccess"
        @close="handleFaceModalClose"
    />
  </div>
</template>

<style lang="less" scoped>
@prefix-cls: ~'@{namespace}-login';

.@{prefix-cls} {
  overflow: auto;

  &__left {
    &::before {
      position: absolute;
      top: 0;
      left: 0;
      z-index: -1;
      width: 100%;
      height: 100%;
      background-image: url('@/assets/svgs/login-bg.svg');
      background-position: center;
      background-repeat: no-repeat;
      content: '';
    }
  }
}
</style>