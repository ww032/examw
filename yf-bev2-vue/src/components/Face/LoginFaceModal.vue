<template>
  <teleport to="body">
    <div class="face-modal-mask" v-if="visible">
      <div class="face-modal">
        <div class="modal-title">人脸验证登录</div>
        <el-form :model="faceForm" :rules="faceRules" ref="faceFormRef" label-width="80px">
          <!-- 身份证输入框 -->
          <el-form-item label="身份证号" prop="idCard">
            <el-input v-model="faceForm.idCard" placeholder="请输入18位身份证号" maxlength="18" />
          </el-form-item>
          <!-- 人脸采集：支持上传图片 + 摄像头拍照 -->
          <el-form-item label="人脸照片" prop="faceImage">
            <div class="face-upload-area">
              <!-- 图片上传 -->
              <el-upload
                  class="avatar-uploader"
                  :show-file-list="false"
                  :before-upload="handleBeforeUpload"
                  accept="image/jpeg,image/png"
              >
                <img v-if="faceForm.faceImage" :src="faceForm.faceImage" class="avatar" />
                <el-icon v-else class="avatar-uploader-icon"><Plus /></el-icon>
              </el-upload>
              <!-- 摄像头拍照（可选，提升体验） -->
              <el-button type="primary" size="small" @click="handleOpenCamera" style="margin-left: 20px;">
                摄像头拍照
              </el-button>
            </div>
            <!-- 摄像头预览区域 -->
            <video v-if="cameraVisible" ref="videoRef" class="camera-video" autoplay playsinline></video>
            <el-button v-if="cameraVisible" type="success" size="small" @click="handleCapture" style="margin-top: 10px;">
              拍摄人脸
            </el-button>
          </el-form-item>
        </el-form>
        <div class="modal-footer">
          <el-button @click="handleClose">取消</el-button>
          <el-button type="primary" @click="handleSubmit" :loading="loading">提交验证</el-button>
        </div>
      </div>
    </div>
  </teleport>
</template>

<script setup lang="ts">
import { ref, reactive, onUnmounted } from 'vue';
import { ElForm, ElFormItem, ElInput, ElUpload, ElButton, ElIcon, ElMessage } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { apiFaceLogin } from '@/api/login'; // 后续新增的人脸识别登录接口

// 对外暴露属性和方法
const emit = defineEmits<{
  (e: 'success'): void;
  (e: 'close'): void;
}>();
const visible = ref(false);
const loading = ref(false);
const originLoginForm = ref<{ account: string; password: string }>({ account: '', password: '' }); // 接收原有登录账号密码

// 弹窗表单数据
const faceFormRef = ref<InstanceType<typeof ElForm>>();
const faceForm = reactive({
  idCard: '',
  faceImage: '' // 存储人脸图片Base64编码
});

// 表单校验规则
const faceRules = reactive({
  idCard: [
    { required: true, message: '请输入身份证号', trigger: 'blur' },
    { pattern: /(^\d{18}$)|(^\d{17}(\d|X|x)$)/, message: '请输入正确的18位身份证号', trigger: 'blur' }
  ],
  faceImage: [
    { required: true, message: '请上传或拍摄人脸照片', trigger: 'change' }
  ]
});

// 摄像头相关
const cameraVisible = ref(false);
const videoRef = ref<HTMLVideoElement | null>(null);
let mediaStream: MediaStream | null = null;

// 打开摄像头
const handleOpenCamera = async () => {
  try {
    // 调用浏览器摄像头API
    mediaStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'user' } }); // 优先前置摄像头
    if (videoRef.value && mediaStream) {
      videoRef.value.srcObject = mediaStream;
      cameraVisible.value = true;
    }
  } catch (error) {
    ElMessage.error('摄像头开启失败，请检查权限或更换浏览器');
    console.error('摄像头开启异常：', error);
  }
};

// 拍摄人脸（转Base64）
const handleCapture = () => {
  if (!videoRef.value || !mediaStream) return;
  // 创建画布，截取视频当前帧
  const canvas = document.createElement('canvas');
  const video = videoRef.value;
  canvas.width = video.videoWidth;
  canvas.height = video.videoHeight;
  const ctx = canvas.getContext('2d');
  if (ctx) {
    ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
    // 转Base64编码（jpeg格式，减小体积）
    faceForm.faceImage = canvas.toDataURL('image/jpeg', 0.8);
    // 关闭摄像头
    handleCloseCamera();
    ElMessage.success('人脸拍摄成功');
  }
};

// 关闭摄像头
const handleCloseCamera = () => {
  if (mediaStream) {
    mediaStream.getTracks().forEach(track => track.stop());
    mediaStream = null;
  }
  cameraVisible.value = false;
};

// 上传图片前置处理（转Base64）
const handleBeforeUpload = (file: File) => {
  const reader = new FileReader();
  reader.onload = (e) => {
    faceForm.faceImage = e.target?.result as string;
  };
  reader.readAsDataURL(file);
  return false; // 阻止默认上传行为，手动处理
};

// 接收原有登录表单数据（对外方法）
const openModal = (loginForm: { account: string; password: string }) => {
  originLoginForm.value = loginForm;
  visible.value = true;
  // 重置表单
  faceForm.idCard = '';
  faceForm.faceImage = '';
  faceFormRef.value?.clearValidate();
};

// 关闭弹窗
const handleClose = () => {
  handleCloseCamera();
  visible.value = false;
  emit('close');
};

// 提交人脸识别验证
const handleSubmit = async () => {
  if (!faceFormRef.value) return;
  faceFormRef.value.validate(async (valid) => {
    if (valid) {
      try {
        loading.value = true;
        // 封装请求参数
        const requestParams = {
          account: originLoginForm.value.account,
          password: originLoginForm.value.password,
          idCard: faceForm.idCard,
          faceImageBase64: faceForm.faceImage.replace(/^data:image\/(jpeg|png);base64,/, '') // 去除Base64前缀，方便后端处理
        };
        // 调用后端人脸识别登录接口
        const res = await apiFaceLogin(requestParams);
        if (res.code === "200") {
          ElMessage.success('人脸识别成功，正在登录...');
          visible.value = false;
          emit('success'); // 通知父组件完成后续登录流程（存储Token、跳转等）
        } else {
          ElMessage.error(res.message || '人脸识别失败');
        }
      } catch (error) {
        ElMessage.error('网络异常或接口调用失败');
        console.error('提交验证异常：', error);
      } finally {
        loading.value = false;
      }
    }
  });
};

// 组件销毁时关闭摄像头
onUnmounted(() => {
  handleCloseCamera();
});

// 对外暴露方法
defineExpose({
  openModal
});
</script>

<style scoped>
.face-modal-mask {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
}

.face-modal {
  width: 500px;
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
}

.modal-title {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 20px;
  text-align: center;
}

.face-upload-area {
  display: flex;
  align-items: center;
}

.avatar-uploader {
  width: 120px;
  height: 120px;
  border: 1px dashed #d9d9d9;
  border-radius: 6px;
  cursor: pointer;
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

.avatar-uploader-icon {
  font-size: 28px;
  color: #8c8c8c;
}

.avatar {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.camera-video {
  width: 300px;
  height: 200px;
  margin-top: 10px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
}

.modal-footer {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>