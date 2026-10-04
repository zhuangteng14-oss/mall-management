<template>
  <div class="goods-up-container">
    <div class="content-box">

      <!-- 顶部 20%：头像 + 商家名 + 右侧下架功能 -->
      <div class="top-section">
        <div class="avatar">
          <img 
            :src="userInfo.image || assetOf('/img/default-avatar.jpg')" 
            alt="头像" 
          />
        </div>
        <div class="shop-name">{{ userInfo.username || '未知商家' }}的小店</div>

        <!-- 右侧：下架商品区域 -->
        <div class="delete-bar">
          <input v-model="delGoodName" placeholder="商品名" class="del-input" />
          <input v-model="delProductCode" placeholder="ProductCode" class="del-input" />
          <button class="del-btn" @click="doDeleteGoods">下架商品</button>
        </div>
      </div>

      <!-- 底部 75%：已上架商品 -->
      <div class="bottom-section">
        <div class="bottom-title">已上架商品</div>
        <div class="bottom-content">
          <div class="empty-tip" v-if="showGoodsList.length === 0">
            暂无任何上架内容
          </div>

          <div class="grid-container" v-else>
            <div 
              class="grid-item" 
              v-for="item in showGoodsList" 
              :key="item.goodName" 
              @click="openModal(item)"
            >
              <div class="item-img">
                <img :src="item.goodImg" alt="商品图" />
              </div>
              <div class="item-info">
                <div class="self-label">自营：{{ item.goodName }}</div>
                <div class="price">
                  ￥<span class="price-int">{{ formatPrice(item.price).int }}</span>
                  <span class="price-dec">.{{ formatPrice(item.price).dec }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 商品编辑弹窗 -->
    <div class="modal-mask" v-if="showModal" @click="closeModal">
      <div class="modal-box" @click.stop>
        <div class="modal-header">
          <h3>商品详情</h3>
          <button class="close-btn" @click="closeModal">×</button>
        </div>

        <div class="modal-body">
          <!-- 商品图片 -->
          <div class="pic-area">
            <img :src="editForm.goodImg" alt="商品" @click="chooseNewPic" />
            <input ref="picFile" type="file" accept="image/*" style="display:none" @change="doChangePic" />
          </div>

          <!-- 商品名称 -->
          <div class="form-line">
            <label>商品名称</label>
            <input v-model="editForm.goodName" />
          </div>

          <!-- 商品类型（锁死） -->
          <div class="form-line">
            <label>商品类型</label>
            <input v-model="editForm.goodType" disabled class="disabled" />
          </div>

          <!-- ProductCode 标签组 -->
          <div class="form-line">
            <label>商品规格</label>
            <div class="code-group">
              <div 
                class="code-item" 
                :class="{active: editForm.productCode == c}"
                v-for="c in editForm.productCodeList"
                :key="c"
                @click="switchPrice(c)"
              >{{ c }}</div>
            </div>
          </div>

          <!-- 价格 -->
          <div class="form-line">
            <label>商品价格</label>
            <input v-model="editForm.price" />
          </div>
        </div>

        <div class="modal-footer">
          <button class="save-btn" @click="doUpdateGoods">更新商品数据</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { http, handleApiError, assetOf } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getMerchantId, getUsername, getPassword } from '../api/auth'

const goodsList = ref([])
const userInfo = ref({})
const showGoodsList = ref([])

// 下架
const delGoodName = ref('')
const delProductCode = ref('')

// 弹窗
const showModal = ref(false)
const editForm = ref({
  goodName: '',
  goodType: '',
  goodImg: '',
  price: '',
  productCode: '',
  productCodeList: [],
})
const picFile = ref(null)

// ------------------------------
// 初始化
// ------------------------------
onMounted(() => {
  getUserInfo()
  getGoodsList()
})

// ------------------------------
// 获取用户信息
// ------------------------------
const getUserInfo = async () => {
  const username = getUsername()
  const password = getPassword()
  if (!username) return

  try {
    const res = await http.post(endpoints.user.getUserInfo, new URLSearchParams({ username, password }))
    const data = await res.json()
    userInfo.value = data
  } catch (e) {
    handleApiError(e, '获取用户信息失败')
  }
}

// ------------------------------
// 获取商品列表 + 同名合并（只显示一个）
// ------------------------------
const getGoodsList = async () => {
  const merchantId = getMerchantId()
  if (!merchantId) return

  try {
    const res = await http.post(endpoints.goods.goodsSelect, new URLSearchParams({ MerchantId: merchantId }))
    const data = await res.json()
    if (data.success) {
      goodsList.value = data.list

      // 同名合并，价格取最后一个
      const map = {}
      data.list.forEach(item => {
        map[item.GoodName] = item
      })
      showGoodsList.value = Object.values(map).map(item => ({
        goodName: item.GoodName,
        price: item.Price,
        goodImg: item.GoodImg,
        goodType: item.GoodType
      }))
    }
  } catch (e) {
    handleApiError(e, '获取商品失败')
  }
}

// ------------------------------
// 价格格式化
// ------------------------------
const formatPrice = (val) => {
  const num = parseFloat(val || 0).toFixed(2)
  const [intPart, decPart] = num.split('.')
  return { int: intPart, dec: decPart }
}

// ------------------------------
// 下架商品
// ------------------------------
const doDeleteGoods = async () => {
  if (!delGoodName.value || !delProductCode.value) {
    alert('请输入商品名和ProductCode')
    return
  }
  const merchantId = getMerchantId()
  try {
    const res = await http.post(endpoints.goods.goodsDe, new URLSearchParams({
        MerchantId: merchantId,
        GoodName: delGoodName.value,
        ProductCode: delProductCode.value
      }))
    const data = await res.json()
    if (data.success) {
      alert('下架成功')
      getGoodsList()
      // 清空输入框
      delGoodName.value = ''
      delProductCode.value = ''
    } else {
      alert('失败：' + data.msg)
    }
  } catch (e) {
    alert('请求失败')
  }
}

// ------------------------------
// 打开弹窗
// ------------------------------
// ------------------------------
// 打开弹窗
// ------------------------------
const openModal = async (item) => {
  const all = goodsList.value.filter(i => i.GoodName === item.goodName)
  const codeList = [...new Set(all.map(i => i.ProductCode))]

  editForm.value = {
    goodName: item.goodName,
    goodType: item.goodType,
    goodImg: item.goodImg,
    price: item.price,
    productCode: codeList[0],
    productCodeList: codeList
  }

  // 🔥 修复：自动加载默认选中的第一个规格价格
  if (codeList.length > 0) {
    await switchPrice(codeList[0])
  }

  showModal.value = true
}

const closeModal = () => {
  showModal.value = false
}


// 切换 ProductCode → 刷新价格 + 图片
const switchPrice = async (code) => {
  const merchantId = getMerchantId()
  try {
    const res = await http.post(endpoints.goods.productCodePrice, new URLSearchParams({
        MerchantId: merchantId,
        GoodName: editForm.value.goodName,
        ProductCode: code
      }))
    const data = await res.json()
    if (data.success) {
      editForm.value.price = data.price       // 价格
      editForm.value.goodImg = data.goodImg   // 🔥 图片同步更新
      editForm.value.productCode = code
    }
  } catch (e) {
    // 空 catch 块修复
  }
}

// ------------------------------
// 更换图片
// ------------------------------
const chooseNewPic = () => {
  picFile.value.click()
}

const doChangePic = (e) => {
  const file = e.target.files[0]
  if (file) {
    editForm.value.goodImg = URL.createObjectURL(file)
  }
}

// ------------------------------
// 更新商品（含图片上传）
// ------------------------------
const doUpdateGoods = async () => {
  const merchantId = getMerchantId()
  const formData = new FormData()

  formData.append('MerchantId', merchantId)
  formData.append('GoodName', editForm.value.goodName)
  formData.append('Price', editForm.value.price)
  formData.append('GoodType', editForm.value.goodType)
  formData.append('ProductCode', editForm.value.productCode)

  if (picFile.value.files.length > 0) {
    formData.append('file', picFile.value.files[0])
  }

  try {
    const res = await http.post(endpoints.goods.goodsUp, formData)
    const data = await res.json()
    if (data.success) {
      alert('更新成功')
      closeModal()
      getGoodsList()
    } else {
      alert('失败：' + data.msg)
    }
  } catch (e) {
    alert('更新失败')
  }
}
</script>

<style scoped>
.goods-up-container {
  width: 100%;
  height: 100%;
  overflow: hidden;
  position: relative;
}

.content-box {
  width: 100%;
  height: 100%;
  background: #fff;
  border-radius: 18px;
  padding: 22px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 5%;
  overflow: hidden;
}

.top-section {
  height: 20%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  flex-shrink: 0;
}

.avatar {
  width: 70px;
  height: 70px;
  border-radius: 50%;
  overflow: hidden;
}
.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  background: #eee;
}

.shop-name {
  font-size: 26px;
  font-weight: bold;
  color: #222;
  flex: 1;
}

.delete-bar {
  display: flex;
  gap: 10px;
}
.del-input {
  padding: 8px 12px;
  border: 1px solid #ddd;
  border-radius: 6px;
  width: 120px;
}
.del-btn {
  background: #e53e3e;
  color: #fff;
  border: none;
  padding: 8px 16px;
  border-radius: 6px;
  cursor: pointer;
}

.bottom-section {
  height: 75%;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}
.bottom-title {
  height: 20%;
  font-size: 22px;
  font-weight: bold;
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
.bottom-content {
  height: 80%;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: 8px;
  box-sizing: border-box;
}

.empty-tip {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
  color: #d0d0d0;
}

.grid-container {
  width: 100%;
  display: flex;
  flex-wrap: wrap;
  row-gap: 16px;
}
.grid-item {
  width: 22%;
  margin: 0 1.5%;
  padding-bottom: 28%;
  position: relative;
  background: #f9f9f9;
  border-radius: 14px;
  overflow: hidden;
  transition: border 0.25s ease;
  cursor: pointer;
}
.grid-item:hover {
  border: 2px solid #008fa8;
}
.item-img {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 60%;
}
.item-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.item-info {
  position: absolute;
  bottom: 0;
  left: 0;
  width: 100%;
  height: 40%;
  padding: 10px 12px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
}
.self-label {
  flex: 0 0 40%;
  font-size: 14px;
  color: #333;
  display: flex;
  align-items: center;
}
.price {
  flex: 0 0 60%;
  color: #e4393c;
  font-weight: bold;
  display: flex;
  align-items: baseline;
}
.price-int {
  font-size: 200%;
}
.price-dec {
  font-size: 100%;
}

/* 弹窗 */
.modal-mask {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0,0,0,0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 999;
}
.modal-box {
  width: 60%;
  height: 100%;
  background: #fff;
  border-radius: 12px;
  display: flex;
  flex-direction: column;
}
.modal-header {
  padding: 20px;
  border-bottom: 1px solid #eee;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.close-btn {
  font-size: 24px;
  background: none;
  border: none;
  cursor: pointer;
}
.modal-body {
  flex: 1;
  padding: 30px;
  overflow-y: auto;
}
.pic-area {
  text-align: center;
  margin-bottom: 20px;
}
.pic-area img {
  width: 200px;
  height: 200px;
  object-fit: cover;
  border-radius: 10px;
  cursor: pointer;
}
.form-line {
  margin-bottom: 20px;
  display: flex;
  align-items: center;
}
.form-line label {
  width: 120px;
  font-weight: bold;
}
.form-line input {
  flex: 1;
  padding: 10px;
  border: 1px solid #ddd;
  border-radius: 6px;
}
input.disabled {
  background: #f5f5f5;
  color: #666;
}
.code-group {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.code-item {
  padding: 6px 14px;
  background: #f0f0f0;
  border-radius: 6px;
  cursor: pointer;
}
.code-item.active {
  background: #007bff;
  color: #fff;
}
.modal-footer {
  padding: 20px;
  text-align: right;
  border-top: 1px solid #eee;
}
.save-btn {
  padding: 10px 24px;
  background: #007bff;
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}
</style>