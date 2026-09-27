<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getPreparations, getSamples, inputResult, moveSample, releaseSample } from '../api'

const bloodType = inject('bloodType')
const samples = ref([])
const preparations = ref([])
const loading = ref(false)
const draggingId = ref(null)
const dragOverLane = ref('')
const detail = ref(null)
const detailVisible = ref(false)

const lanes = [
  { key: 'PENDING', title: '待检', cls: 'pending', desc: '标本已贴码，等待上机' },
  { key: 'TESTING', title: '检测中', cls: 'testing', desc: 'ALT / 血红蛋白 / HBV / HCV / HIV / 梅毒' },
  { key: 'RELEASED', title: '已放行', cls: 'released', desc: '复核通过后自动生成成分制备单' }
]

const grouped = computed(() => ({
  PENDING: samples.value.filter((s) => s.status === 'PENDING'),
  TESTING: samples.value.filter((s) => s.status === 'TESTING'),
  RELEASED: samples.value.filter((s) => s.status === 'RELEASED'),
  SCRAPPED: samples.value.filter((s) => s.status === 'SCRAPPED')
}))

// ---------------- HTML5 原生拖拽 ----------------
function onDragStart(sample, event) {
  draggingId.value = sample.id
  event.dataTransfer.effectAllowed = 'move'
  event.dataTransfer.setData('text/plain', String(sample.id))
}

function onDragEnd() {
  draggingId.value = null
  dragOverLane.value = ''
}

function onDragOver(lane, event) {
  event.preventDefault()
  event.dataTransfer.dropEffect = 'move'
  dragOverLane.value = lane
}

function onDragLeave() {
  dragOverLane.value = ''
}

async function onDrop(lane) {
  const id = draggingId.value
  dragOverLane.value = ''
  draggingId.value = null
  if (!id) return
  const sample = samples.value.find((s) => s.id === id)
  if (!sample || sample.status === lane) return
  await doMove(sample, lane)
}

async function doMove(sample, lane) {
  if (lane === 'RELEASED') {
    try {
      const { value } = await ElMessageBox.prompt(
        `标本 ${sample.barcode}（${sample.bloodType}）放入「已放行」将即时触发成分制备，请输入复核人：`,
        '选择复核人',
        { inputPlaceholder: '例如 林岚', inputValue: '林岚' }
      )
      const res = await releaseSample(sample.id, value)
      ElMessage.success(res.message || '已放行')
    } catch (e) {
      if (e !== 'cancel' && e.message) ElMessage.error(e.message)
      return
    }
  } else {
    try {
      const res = await moveSample(sample.id, lane)
      ElMessage.success(res.message || '状态已更新')
    } catch (e) {
      ElMessage.error(e.message)
      return
    }
  }
  load()
}

// ---------------- 检验结果录入 ----------------
const resultVisible = ref(false)
const resultForm = ref({ id: null, altValue: 22, hbValue: 135, hbvResult: 'NEGATIVE', hcvResult: 'NEGATIVE', hivResult: 'NEGATIVE', syphilisResult: 'NEGATIVE', operator: '林岚' })

function openResult(sample) {
  resultForm.value = {
    id: sample.id,
    altValue: 22,
    hbValue: 135,
    hbvResult: 'NEGATIVE',
    hcvResult: 'NEGATIVE',
    hivResult: 'NEGATIVE',
    syphilisResult: 'NEGATIVE',
    operator: sample.reviewer || '林岚'
  }
  resultVisible.value = true
}

async function submitResult() {
  try {
    const res = await inputResult(resultForm.value.id, resultForm.value)
    ElMessage.success(res.message || '结果已录入')
    resultVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openDetail(sample) {
  detail.value = sample
  detailVisible.value = true
}

async function load() {
  loading.value = true
  try {
    const [s, p] = await Promise.all([getSamples(bloodType.value), getPreparations(bloodType.value)])
    samples.value = s.data || []
    preparations.value = (p.data || []).slice(0, 8)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(bloodType, load)
</script>

<template>
  <div v-loading="loading">
    <div class="bc-alert">
      三条轨道分别代表 <b>待检 / 检测中 / 已放行</b>：按住标本管拖动即可流转；落入「已放行」需选择复核人，并即时触发成分制备（领域事件）。
    </div>

    <div
      v-for="lane in lanes"
      :key="lane.key"
      class="bc-lane"
      :class="lane.cls"
    >
      <div class="bc-lane-head">
        <span class="bc-lane-title">{{ lane.title }}</span>
        <span class="bc-lane-count">{{ grouped[lane.key].length }}</span>
        <span class="bc-panel-sub">{{ lane.desc }}</span>
      </div>
      <div
        class="bc-lane-body"
        :class="{ dragover: dragOverLane === lane.key }"
        @dragover="onDragOver(lane.key, $event)"
        @dragleave="onDragLeave"
        @drop="onDrop(lane.key)"
      >
        <div
          v-for="s in grouped[lane.key]"
          :key="s.id"
          class="bc-tube"
          :class="{ dragging: draggingId === s.id, positive: s.hbvResult === 'POSITIVE' || s.hcvResult === 'POSITIVE' || s.hivResult === 'POSITIVE' || s.syphilisResult === 'POSITIVE' }"
          draggable="true"
          @dragstart="onDragStart(s, $event)"
          @dragend="onDragEnd"
          @click="openDetail(s)"
        >
          <div class="barcode"></div>
          <div class="info">
            <div class="code">{{ s.barcode }}</div>
            <div class="who">{{ s.donorName }} · {{ s.reviewer ? '复核 ' + s.reviewer : '未复核' }}</div>
          </div>
          <div class="blood">{{ s.bloodType }}</div>
        </div>
        <div v-if="!grouped[lane.key].length" class="bc-empty">拖动标本管到这里</div>
      </div>
    </div>

    <!-- 已报废标本（只读） -->
    <div class="bc-lane scrapped">
      <div class="bc-lane-head">
        <span class="bc-lane-title">已报废</span>
        <span class="bc-lane-count">{{ grouped.SCRAPPED.length }}</span>
        <span class="bc-panel-sub">检验不合格（ALT 超标 / 血红蛋白偏低 / 传染病阳性）</span>
      </div>
      <div class="bc-lane-body">
        <div v-for="s in grouped.SCRAPPED" :key="s.id" class="bc-tube positive" @click="openDetail(s)">
          <div class="barcode"></div>
          <div class="info">
            <div class="code">{{ s.barcode }}</div>
            <div class="who">{{ s.donorName }} · 判定不合格</div>
          </div>
          <div class="blood">{{ s.bloodType }}</div>
        </div>
        <div v-if="!grouped.SCRAPPED.length" class="bc-empty">暂无报废标本</div>
      </div>
    </div>

    <div class="bc-panel">
      <div class="bc-panel-head">
        <div class="bc-panel-title">最近生成的成分制备批次</div>
        <div class="spacer"></div>
        <span class="bc-panel-sub">由标本放行事件自动写入</span>
      </div>
      <el-table :data="preparations" size="small" stripe>
        <el-table-column prop="batchNo" label="批号" width="130" />
        <el-table-column prop="collectNo" label="全血来源" width="120" />
        <el-table-column prop="bloodType" label="血型" width="60" />
        <el-table-column label="红细胞" width="110">
          <template #default="{ row }">{{ row.rbcBags }} 袋 / {{ row.rbcVolumeMl }} ml</template>
        </el-table-column>
        <el-table-column label="血浆" width="110">
          <template #default="{ row }">{{ row.plasmaBags }} 袋 / {{ row.plasmaVolumeMl }} ml</template>
        </el-table-column>
        <el-table-column label="血小板" width="110">
          <template #default="{ row }">{{ row.plateletBags }} 袋 / {{ row.plateletVolumeMl }} ml</template>
        </el-table-column>
        <el-table-column label="冷沉淀" width="110">
          <template #default="{ row }">{{ row.cryoBags }} 袋 / {{ row.cryoVolumeMl }} ml</template>
        </el-table-column>
        <el-table-column prop="operator" label="制备人" width="110" />
      </el-table>
    </div>

    <!-- 标本明细：六项检验结果 -->
    <el-drawer v-model="detailVisible" title="标本检验明细" size="420px">
      <div v-if="detail">
        <div class="bc-alert">
          {{ detail.barcode }} · {{ detail.donorName }} · {{ detail.bloodType }} ·
          <b>{{ detail.statusName }}</b>
        </div>
        <el-table :data="detail.items" size="small">
          <el-table-column prop="name" label="项目" width="110" />
          <el-table-column label="结果" width="110">
            <template #default="{ row }">{{ row.value }}{{ row.unit }}</template>
          </el-table-column>
          <el-table-column prop="conclusion" label="判定" />
        </el-table>
        <div style="margin-top: 14px; display: flex; gap: 8px">
          <el-button type="primary" @click="openResult(detail)">录入/重录结果</el-button>
          <el-button
            v-if="detail.status === 'TESTING'"
            type="success"
            @click="doMove(detail, 'RELEASED'); detailVisible = false"
          >
            放行并制备
          </el-button>
        </div>
      </div>
    </el-drawer>

    <!-- 检验结果录入 -->
    <el-dialog v-model="resultVisible" title="录入检验结果" width="520px">
      <el-form label-width="92px">
        <el-form-item label="ALT">
          <el-input-number v-model="resultForm.altValue" :min="1" :max="200" :step="0.1" />
          <span style="margin-left: 8px; color: #8d6e63">U/L（&gt; 40 判不合格）</span>
        </el-form-item>
        <el-form-item label="血红蛋白">
          <el-input-number v-model="resultForm.hbValue" :min="60" :max="200" :step="0.5" />
          <span style="margin-left: 8px; color: #8d6e63">g/L</span>
        </el-form-item>
        <el-form-item label="HBV">
          <el-radio-group v-model="resultForm.hbvResult">
            <el-radio value="NEGATIVE">阴性</el-radio>
            <el-radio value="POSITIVE">阳性</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="HCV">
          <el-radio-group v-model="resultForm.hcvResult">
            <el-radio value="NEGATIVE">阴性</el-radio>
            <el-radio value="POSITIVE">阳性</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="HIV">
          <el-radio-group v-model="resultForm.hivResult">
            <el-radio value="NEGATIVE">阴性</el-radio>
            <el-radio value="POSITIVE">阳性</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="梅毒">
          <el-radio-group v-model="resultForm.syphilisResult">
            <el-radio value="NEGATIVE">阴性</el-radio>
            <el-radio value="POSITIVE">阳性</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="检验人">
          <el-input v-model="resultForm.operator" style="width: 160px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resultVisible = false">取消</el-button>
        <el-button type="primary" @click="submitResult">保存结果</el-button>
      </template>
    </el-dialog>
  </div>
</template>
