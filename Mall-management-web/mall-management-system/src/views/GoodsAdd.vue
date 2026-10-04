<template>
  <div class="goods-add-page">
    <div class="card bottom-card">
      <h2>商品上架</h2>

      <div class="form-group">
        <div class="info-row">
          <label>商品名称</label>
          <input
            class="info-input"
            v-model="GoodName"
            type="text"
            placeholder="请输入商品名称"
          />
        </div>
        <div class="tip-red" v-if="showTip && !GoodName">
          请输入商品名称
        </div>
      </div>

      <div class="form-group">
        <div class="info-row">
          <label>商品价格</label>
          <input
            class="info-input"
            v-model="Price"
            type="number"
            placeholder="请输入商品价格"
          />
        </div>
        <div class="tip-red" v-if="showTip && !Price">
          请输入商品价格
        </div>
      </div>

      <div class="form-group">
        <div class="info-row">
          <label>商品图片</label>
          <div class="img-upload-box" @click="chooseImage">
            <img v-if="previewImg" :src="previewImg" class="preview-img" />
            <span v-else>点击选择图片</span>
            <input
              ref="fileInput"
              type="file"
              accept="image/*"
              style="display: none"
              @change="handleImageChange"
            />
          </div>
        </div>
        <div class="tip-red" v-if="showTip && !selectedFile">
          请选择商品图片
        </div>
      </div>

      <div class="form-group">
        <div class="info-row type-section">
          <label>商品类型</label>
          <div class="radio-group">
            <div class="radio-list">
              <label v-for="item in typeList" :key="item.Id">
                <input
                  v-model="GoodType"
                  type="radio"
                  :value="item.GoodType"
                />
                {{ item.GoodType }}
              </label>
              <label v-if="tempNewType">
                <input
                  v-model="GoodType"
                  type="radio"
                  :value="tempNewType"
                />
                {{ tempNewType }}（新类型）
              </label>
            </div>
            <div class="add-type-box">
              <input v-model="newType" placeholder="输入新类型名" />
              <button @click="addTempType">临时添加类型</button>
            </div>
          </div>
        </div>
        <div class="tip-red" v-if="showTip && !GoodType">
          请选择商品类型
        </div>
      </div>

      <div class="form-group">
        <div class="info-row">
          <label>商品型号</label>
          <input
            class="info-input"
            v-model="ProductCode"
            type="text"
            placeholder="请输入商品型号"
          />
        </div>
        <div class="tip-red" v-if="showTip && !ProductCode">
          请输入商品型号
        </div>
      </div>

      <div class="save-btn-wrap">
        <button class="save-info-btn" @click="submitGoods">上架商品</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { http, handleApiError } from '../api/http'
import { endpoints } from '../api/endpoints'
import { getMerchantId } from '../api/auth'

const fileInput = ref(null)
const previewImg = ref('')
const selectedFile = ref(null)

const GoodName = ref('')
const Price = ref('')
const GoodType = ref('')
const ProductCode = ref('')
const GoodTypeNew = ref(0)

const typeList = ref([])
const newType = ref('')
const tempNewType = ref('')
const showTip = ref(false)

onMounted(() => {
  loadGoodsType()
})

const loadGoodsType = async () => {
  try {
    const res = await http.get(endpoints.goods.goodstype)
    const data = await res.json()
    if (data.success) {
      typeList.value = data.list
    }
  } catch (err) {
    handleApiError(err, '加载类型失败')
  }
}

const addTempType = () => {
  if (!newType.value) return
  tempNewType.value = newType.value
  GoodType.value = newType.value
  GoodTypeNew.value = 1
  newType.value = ''
}

const chooseImage = () => {
  fileInput.value.click()
}

const handleImageChange = (e) => {
  const file = e.target.files[0]
  if (!file) return
  selectedFile.value = file
  previewImg.value = URL.createObjectURL(file)
}

const saveNewTypeToDB = async (typeName) => {
  try {
    await http.post(endpoints.goods.goodstype, new URLSearchParams({
        GoodType: typeName,
        GoodTypeNew: 1
      }))
  } catch (e) {
    handleApiError(e, '保存类型失败')
  }
}

// ✅ 最终修复版：完全适配你的后端接口
const submitGoods = async () => {
  showTip.value = true

  if (!GoodName.value || !Price.value || !selectedFile.value || !GoodType.value || !ProductCode.value) {
    return
  }

  const needSaveNewType = tempNewType.value && GoodType.value === tempNewType.value
  const merchantid = getMerchantId()

  // ==============================================
  // 🔥 核心修复：从数据库取真实 GoodTypeNew
  // ==============================================
  let realGoodTypeNew = 0

  // 如果是本次临时新增的类型 → 1
  if (needSaveNewType) {
    realGoodTypeNew = 1
  } 
  // 如果是数据库里已有的类型 → 用数据库里的值
  else {
    const type = typeList.value.find(t => t.GoodType === GoodType.value)
    if (type) {
      realGoodTypeNew = type.GoodTypeNew
    }
  }

  const formData = new FormData()
  formData.append('MerchantId', merchantid)
  formData.append('GoodName', GoodName.value)
  formData.append('Price', Price.value)
  formData.append('file', selectedFile.value)
  formData.append('GoodType', GoodType.value)
  formData.append('ProductCode', ProductCode.value)
  formData.append('GoodTypeNew', realGoodTypeNew) // 用真实值！

  try {
    const res = await http.post(endpoints.goods.goodsAdd, formData)
    const data = await res.json()

    if (data.success) {
      if (needSaveNewType) {
        await saveNewTypeToDB(tempNewType.value)
      }

      alert('商品上架成功！')
      GoodName.value = ''
      Price.value = ''
      GoodType.value = ''
      ProductCode.value = ''
      previewImg.value = ''
      selectedFile.value = null
      tempNewType.value = ''
      showTip.value = false

      loadGoodsType()
    } else {
      alert('失败：' + data.msg)
    }
  } catch (err) {
    alert('上架请求失败')
  }
}
</script>

<style scoped>
.goods-add-page {
  width: 90vw;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.card {
  width: 100%;
  background: #fff;
  border-radius: 18px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.06);
  padding: 26px;
  box-sizing: border-box;
}

/* 每组输入框独立分组，间距统一 */
.form-group {
  margin-bottom: 24px;
}

.info-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.info-row label {
  width: 70px;
  font-weight: 500;
  flex-shrink: 0;
}

.info-input {
  width: 60vw;
  padding: 10px 12px;
  border: 1px solid #eee;
  border-radius: 6px;
  outline: none;
}

.tip-red {
  color: red;
  font-size: 12px;
  margin-left: 80px;
  margin-top: 6px;
  height: 16px;
}

.img-upload-box {
  width: 100px;
  height: 100px;
  border: 1px dashed #ccc;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  font-size: 12px;
  color: #888;
}

.preview-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.type-section {
  align-items: flex-start;
}

.radio-group {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.radio-list {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.add-type-box {
  display: flex;
  gap: 6px;
}

.add-type-box input {
  padding: 6px 10px;
  border: 1px solid #ddd;
  border-radius: 4px;
}

.add-type-box button {
  padding: 6px 10px;
  background: #007bff;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}

.save-btn-wrap {
  width: 60vw;
  margin-left: 70px;
  margin-top: 10px;
}

.save-info-btn {
  background-color: #e53e3e;
  color: #fff;
  border: none;
  padding: 10px 22px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
}
</style>