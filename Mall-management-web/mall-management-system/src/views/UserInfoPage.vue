<template>
  <div class="user-info-page">
    <div class="card top-card">
      <div class="avatar-wrap">
        <img 
          class="avatar" 
          :src="userInfo.image || assetOf('/img/default_user.png')" 
          alt="头像" 
        />
        <div class="username-box">
          <span class="username-text">{{ userInfo.username || '未知用户' }}</span>
          <button class="change-avatar-btn" @click="chooseImage">
            更换头像照片
          </button>
          <input 
            ref="fileInput" 
            type="file" 
            accept="image/*" 
            style="display:none" 
            @change="doUpload"
          >
        </div>
      </div>
      <div class="id-badge">{{ idType }}：{{ idValue }}</div>
    </div>

    <div class="card bottom-card">
      <div class="info-row">
        <label>用户类型</label>
        <span>{{ userInfo.usertype || '无' }}</span>
      </div>
      <div class="info-row">
        <label>性别</label>
        <span>{{ userInfo.gender || '无' }}</span>
      </div>
      <div class="info-row">
        <label>年龄</label>
        <span>{{ userInfo.age || 0 }}</span>
      </div>

      <div class="info-row">
        <label>邮箱</label>
        <input 
          type="text" 
          class="info-input" 
          v-model="userInfo.email"
          placeholder="暂未填写邮箱"
        />
      </div>

      <div class="info-row">
        <label>常用地址</label>
        <input 
          type="text" 
          class="info-input" 
          v-model="userInfo.address"
          placeholder="暂未填写地址"
        />
      </div>

      <!-- 🔥 修复这里：introduction → selfintroduction -->
      <div class="info-row textarea-row">
        <label>自我介绍</label>
        <textarea 
          class="info-textarea" 
          v-model="userInfo.selfintroduction"
          placeholder="暂无自我介绍"
        ></textarea>
      </div>

      <div class="save-btn-wrap">
        <button class="save-info-btn" @click="saveUserInfo">保存个人信息</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { http, handleApiError, assetOf } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getUsername, getPassword } from '../api/auth'

const userInfo = ref({})
const idType = ref('')
const idValue = ref('')
const fileInput = ref(null)

onMounted(() => {
  getUserData()
})

const getUserData = async () => {
  const username = getUsername()
  const password = getPassword()
  if (!username) return

  try {
    const res = await http.post(endpoints.user.getUserInfo, new URLSearchParams({ username, password }))
    const data = await res.json()
    userInfo.value = data

    if (data.customerid) {
      idType.value = '客户ID'
      idValue.value = data.customerid
    } else if (data.merchantid) {
      idType.value = '商家ID'
      idValue.value = data.merchantid
    }
  } catch (e) {
    handleApiError(e, '获取用户信息失败')
  }
}

const chooseImage = () => {
  fileInput.value.click()
}

const doUpload = async (e) => {
  const file = e.target.files[0]
  if (!file) return

  const username = getUsername()
  const formData = new FormData()
  formData.append('username', username)
  formData.append('file', file)

  try {
    const res = await http.post(endpoints.user.updateAvatar, formData)
    const data = await res.json()
    if (data.success) {
      alert('头像更新成功')
      userInfo.value.image = data.imageUrl
    } else {
      alert('失败：' + data.msg)
    }
  } catch (err) {
    alert('上传失败')
  }
}

const saveUserInfo = async () => {
  const username = getUsername()
  try {
    const res = await http.post(endpoints.user.updateUserInfo, new URLSearchParams({
        username: username,
        email: userInfo.value.email || '',
        address: userInfo.value.address || '',
        selfintroduction: userInfo.value.selfintroduction || ''
      }))
    const data = await res.json()
    if (data.success) {
      alert('个人信息保存成功')
      getUserData() // 刷新最新数据
    } else {
      alert('保存失败：' + data.msg)
    }
  } catch (err) {
    alert('保存请求异常')
  }
}
</script>

<style scoped>
.user-info-page {
  width: 90vw;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.card {
  width: 100%;
  background: #fff;
  border-radius: 18px;
  box-shadow: 0 6px 20px rgba(0,0,0,0.06);
  padding: 26px;
  box-sizing: border-box;
  position: relative;
}
.avatar-wrap {
  display: flex;
  align-items: center;
  gap: 18px;
}
.avatar {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  object-fit: cover;
  background: #eee;
}
.username-box {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.username-text {
  font-size: 19px;
  font-weight: 500;
}
.change-avatar-btn {
  background: #e53e3e;
  color: #fff;
  border: none;
  padding: 6px 14px;
  border-radius: 6px;
  cursor: pointer;
}
.id-badge {
  position: absolute;
  right: 26px;
  bottom: 18px;
  font-size: 12px;
  color: #999;
}
.bottom-card {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.info-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.textarea-row {
  align-items: flex-start;
}
.info-row label {
  width: 70px;
  font-weight: 500;
  flex-shrink: 0;
}
.info-input, .info-textarea {
  width: 60vw;
  padding: 8px 12px;
  border: 1px solid #eee;
  border-radius: 6px;
  outline: none;
}
.info-textarea {
  height: 80px;
  resize: vertical;
}
.save-btn-wrap {
  width: 60vw;
  margin-left: 70px;
  margin-top: 12px;
}
.save-info-btn {
  background-color: #e53e3e;
  color: #fff;
  border: none;
  padding: 8px 20px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
}
.save-info-btn:hover {
  background-color: #c53030;
}
</style>