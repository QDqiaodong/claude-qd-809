<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fmtDateTime,
  getIssues,
  getReactionDetail,
  getReactionTrace,
  getReactions,
  reportReaction,
  reviewReaction
} from '../api'
import SourceChain from '../components/SourceChain.vue'

const bloodType = inject('bloodType')
const issued = ref([])
const events = ref([])
const detail = ref(null)
const activeId = ref(null)
const loading = ref(false)
const detailLoading = ref(false)

const SYMPTOM_PRESETS = [
  '发热', '寒战', '皮疹荨麻疹', '呼吸困难', '低血压',
  '血红蛋白尿', '黄疸', '腰痛', '恶心呕吐', '过敏性休克'
]

/* 上报对话框 */
const reportVisible = ref(false)
const reportSubmitting = ref(false)
const reportMatch = ref(null)
const reportTrace = ref(null)
const symptomChoices = ref([])
const reportForm = ref({ occurredAt: '', hospital: '', reporter: '' })

/* 复核对话框 */
const reviewVisible = ref(false)
const reviewSubmitting = ref(false)
const reviewForm = ref({ conclusion: 'EXCLUDED', reviewer: '', note: '' })

const issuedFiltered = computed(() =>
  bloodType.value ? issued.value.filter((r) => r.bloodType === bloodType.value) : issued.value
)

/** 每条发血记录的在办事件号（用于「已有在办事件」提示） */
const openEventByMatch = computed(() => {
  const map = {}
  events.value.forEach((e) => {
    if (e.status === 'OPEN') map[e.matchId] = e.eventNo
  })
  return map
})

async function load() {
  loading.value = true
  try {
    const [i, e] = await Promise.all([getIssues(), getReactions(bloodType.value)])
    issued.value = i.data || []
    events.value = e.data || []
    if (activeId.value && !events.value.some((x) => x.id === activeId.value)) {
      activeId.value = null
      detail.value = null
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function selectEvent(row) {
  activeId.value = row.id
  detailLoading.value = true
  try {
    const res = await getReactionDetail(row.id)
    detail.value = res.data
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    detailLoading.value = false
  }
}

function defaultOccurredAt() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:00`
}

async function openReport(row) {
  reportMatch.value = row
  reportForm.value = { occurredAt: defaultOccurredAt(), hospital: row.hospital || '', reporter: '' }
  symptomChoices.value = []
  reportTrace.value = null
  reportVisible.value = true
  try {
    const res = await getReactionTrace(row.id)
    reportTrace.value = res.data
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function submitReport() {
  const symptoms = [...symptomChoices.value].sort().join('、')
  if (!symptoms) {
    ElMessage.warning('请勾选或输入反应症状')
    return
  }
  if (!reportForm.value.occurredAt) {
    ElMessage.warning('请选择反应发生时间')
    return
  }
  if (!reportForm.value.hospital.trim()) {
    ElMessage.warning('请填写上报医院')
    return
  }
  if (!reportForm.value.reporter.trim()) {
    ElMessage.warning('请填写处置人')
    return
  }
  reportSubmitting.value = true
  try {
    const res = await reportReaction({
      matchId: reportMatch.value.id,
      symptoms,
      occurredAt: reportForm.value.occurredAt,
      hospital: reportForm.value.hospital.trim(),
      reporter: reportForm.value.reporter.trim()
    })
    const r = res.data
    reportVisible.value = false
    if (r.duplicated) {
      ElMessage.warning(res.message)
    } else if (r.siblingEventNo) {
      ElMessageBox.alert(res.message, '已登记为独立事件', { type: 'warning', confirmButtonText: '知道了' })
    } else {
      ElMessage.success(res.message)
    }
    await load()
    await selectEvent({ id: r.event.id })
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    reportSubmitting.value = false
  }
}

function openReview() {
  reviewForm.value = { conclusion: 'EXCLUDED', reviewer: '', note: '' }
  reviewVisible.value = true
}

async function submitReview() {
  if (!reviewForm.value.reviewer.trim()) {
    ElMessage.warning('请填写质量复核人')
    return
  }
  if (reviewForm.value.conclusion === 'CONFIRMED') {
    try {
      await ElMessageBox.confirm(
        '确认风险后，本事件隔离中的血袋将全部转为报废并保留处置链，该操作不可回退。确定继续？',
        '确认风险',
        { type: 'warning', confirmButtonText: '确认风险并报废', cancelButtonText: '再想想' }
      )
    } catch {
      return
    }
  }
  reviewSubmitting.value = true
  try {
    const res = await reviewReaction(activeId.value, {
      conclusion: reviewForm.value.conclusion,
      reviewer: reviewForm.value.reviewer.trim(),
      note: reviewForm.value.note
    })
    ElMessage.success(res.message)
    reviewVisible.value = false
    detail.value = res.data
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    reviewSubmitting.value = false
  }
}

function eventTagType(status) {
  return status === 'OPEN' ? 'danger' : status === 'CONFIRMED' ? 'info' : 'success'
}

function isolationTagType(status) {
  return { ISOLATED: 'danger', RELEASED: 'success', SCRAPPED: 'info', TRACE: 'warning' }[status] || 'info'
}

function stepTagType(action) {
  return (
    { REPORT: 'primary', ISOLATE: 'danger', TRACE: 'info', RELEASE: 'success', SCRAP: 'warning', KEEP: 'info', REVIEW: 'primary' }[action] || 'info'
  )
}

onMounted(load)
watch(bloodType, load)
</script>

<template>
  <div v-loading="loading">
    <div class="bc-alert">
      医院针对<b>已发血记录</b>上报不良反应，相同记录 + 相同发生时间 + 相同症状只形成一宗事件；
      建单即对<b>同一采血来源仍未发出</b>的成分血袋建立同源隔离（配血与发血一律拒绝），
      已发出 / 已报废血袋只进追溯清单；质量复核「排除关联」释放合格血袋、「确认风险」转报废并保留处置链。
    </div>

    <!-- 已发血记录：上报入口 -->
    <div class="bc-panel">
      <div class="bc-panel-head">
        <div class="bc-panel-title">已发血记录（点击行内按钮上报反应）</div>
        <div class="spacer"></div>
        <span class="bc-panel-sub">共 {{ issuedFiltered.length }} 条已出库记录</span>
      </div>
      <el-table :data="issuedFiltered" size="small" stripe max-height="260">
        <el-table-column prop="matchNo" label="配血单号" width="120" />
        <el-table-column prop="bagNo" label="供血袋号" width="110" />
        <el-table-column prop="recipient" label="受血者" width="90" />
        <el-table-column prop="hospital" label="医院" min-width="140" show-overflow-tooltip />
        <el-table-column prop="bloodType" label="血型" width="64" />
        <el-table-column prop="componentName" label="成分" width="100" />
        <el-table-column prop="unitsU" label="U" width="56" />
        <el-table-column label="发血时间" width="140">
          <template #default="{ row }">{{ fmtDateTime(row.issueTime) }}</template>
        </el-table-column>
        <el-table-column label="在办事件" width="130">
          <template #default="{ row }">
            <el-tag v-if="openEventByMatch[row.id]" size="small" type="danger">
              {{ openEventByMatch[row.id] }}
            </el-tag>
            <span v-else>--</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="danger" plain @click="openReport(row)">上报反应</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 处置事件台账 -->
    <div class="bc-panel">
      <div class="bc-panel-head">
        <div class="bc-panel-title">处置事件台账</div>
        <div class="spacer"></div>
        <span class="bc-panel-sub">点一行查看来源链与处置明细</span>
      </div>
      <el-table
        :data="events"
        size="small"
        stripe
        highlight-current-row
        @row-click="selectEvent"
        :row-class-name="({ row }) => (row.id === activeId ? 'current-row' : '')"
      >
        <el-table-column prop="eventNo" label="事件编号" width="140" />
        <el-table-column prop="matchNo" label="配血单号" width="120" />
        <el-table-column prop="bagNo" label="供血袋号" width="110" />
        <el-table-column prop="hospital" label="上报医院" min-width="130" show-overflow-tooltip />
        <el-table-column prop="symptoms" label="症状" min-width="150" show-overflow-tooltip />
        <el-table-column label="发生时间" width="140">
          <template #default="{ row }">{{ fmtDateTime(row.occurredAt) }}</template>
        </el-table-column>
        <el-table-column prop="reporter" label="处置人" width="90" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="dark" :type="eventTagType(row.status)">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="隔离/释放/报废/追溯" width="180">
          <template #default="{ row }">
            <span class="bc-count danger">{{ row.isolatedCount }}</span> /
            <span class="bc-count success">{{ row.releasedCount }}</span> /
            <span class="bc-count info">{{ row.scrappedCount }}</span> /
            <span class="bc-count warn">{{ row.traceCount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="reviewer" label="复核人" width="90">
          <template #default="{ row }">{{ row.reviewer || '--' }}</template>
        </el-table-column>
      </el-table>
      <div v-if="!events.length" class="bc-empty">暂无不良反应事件</div>
    </div>

    <!-- 事件详情 -->
    <template v-if="detail">
      <div class="bc-panel" v-loading="detailLoading">
        <div class="bc-panel-head">
          <div class="bc-panel-title">
            事件 {{ detail.event.eventNo }}
            <el-tag size="small" effect="dark" :type="eventTagType(detail.event.status)" style="margin-left: 8px">
              {{ detail.event.statusName }}
            </el-tag>
          </div>
          <div class="spacer"></div>
          <el-button v-if="detail.event.status === 'OPEN'" size="small" type="primary" @click="openReview">
            质量复核
          </el-button>
          <el-tag v-else size="small" type="info">已结案：{{ detail.event.conclusionName }}</el-tag>
        </div>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="发血记录">{{ detail.event.matchNo }}（袋 {{ detail.event.bagNo }}）</el-descriptions-item>
          <el-descriptions-item label="上报医院">{{ detail.event.hospital }}</el-descriptions-item>
          <el-descriptions-item label="处置人">{{ detail.event.reporter }}</el-descriptions-item>
          <el-descriptions-item label="反应症状" :span="2">{{ detail.event.symptoms }}</el-descriptions-item>
          <el-descriptions-item label="发生时间">{{ fmtDateTime(detail.event.occurredAt) }}</el-descriptions-item>
          <el-descriptions-item label="上报时间">{{ fmtDateTime(detail.event.reportedAt) }}</el-descriptions-item>
          <el-descriptions-item label="复核人">{{ detail.event.reviewer || '--' }}</el-descriptions-item>
          <el-descriptions-item label="复核时间">{{ fmtDateTime(detail.event.reviewedAt) }}</el-descriptions-item>
          <el-descriptions-item label="复核备注" :span="3">{{ detail.event.reviewNote || '--' }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <!-- 来源链 -->
      <div class="bc-panel">
        <div class="bc-panel-head">
          <div class="bc-panel-title">来源链（发血记录 → 血袋 → 制备批次 → 采血记录 → 检验标本）</div>
        </div>
        <SourceChain :chain="detail.chain" />
      </div>

      <!-- 同源血袋隔离清单 -->
      <div class="bc-panel">
        <div class="bc-panel-head">
          <div class="bc-panel-title">同源血袋隔离清单</div>
          <div class="spacer"></div>
          <span class="bc-panel-sub">已发出 / 已报废血袋只进追溯清单，不改写状态</span>
        </div>
        <el-table :data="detail.isolations" size="small" stripe>
          <el-table-column prop="bagNo" label="袋号" width="120" />
          <el-table-column prop="bloodType" label="血型" width="64" />
          <el-table-column prop="componentName" label="成分" width="100" />
          <el-table-column prop="volumeMl" label="容量 ml" width="90" />
          <el-table-column label="登记时状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" type="info" plain>{{ row.registerStatusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="当前处置" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="dark" :type="isolationTagType(row.status)">{{ row.statusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="登记时间" width="150">
            <template #default="{ row }">{{ fmtDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="了结时间" width="150">
            <template #default="{ row }">{{ fmtDateTime(row.resolvedAt) }}</template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 处置台账 -->
      <div class="bc-panel">
        <div class="bc-panel-head">
          <div class="bc-panel-title">处置台账（逐步记录，只增不改）</div>
        </div>
        <el-timeline class="bc-steps">
          <el-timeline-item
            v-for="step in detail.steps"
            :key="step.id"
            :timestamp="fmtDateTime(step.createdAt) + ' · ' + (step.operator || '系统')"
            :type="stepTagType(step.action)"
          >
            <el-tag size="small" :type="stepTagType(step.action)" effect="plain">{{ step.actionName }}</el-tag>
            <span v-if="step.bagNo" class="bc-step-bag">{{ step.bagNo }}</span>
            {{ step.detail }}
          </el-timeline-item>
        </el-timeline>
      </div>
    </template>
    <div v-else class="bc-panel">
      <div class="bc-empty">选择上方任一处置事件，查看来源链、同源隔离清单与处置台账</div>
    </div>

    <!-- 上报反应对话框 -->
    <el-dialog v-model="reportVisible" title="上报输血不良反应" width="720px">
      <div v-if="reportMatch" class="bc-report-head">
        发血记录 <b>{{ reportMatch.matchNo }}</b> · 供血袋 <b>{{ reportMatch.bagNo }}</b> ·
        受血者 <b>{{ reportMatch.recipient }}</b> · {{ reportMatch.hospital }}
      </div>
      <el-form label-width="96px" style="margin-top: 12px">
        <el-form-item label="反应症状" required>
          <el-select
            v-model="symptomChoices"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入症状（可多选，可输入自定义症状）"
            style="width: 100%"
          >
            <el-option v-for="s in SYMPTOM_PRESETS" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="发生时间" required>
          <el-date-picker
            v-model="reportForm.occurredAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            format="YYYY-MM-DD HH:mm"
            placeholder="选择反应发生时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="上报医院" required>
          <el-input v-model="reportForm.hospital" placeholder="上报医院" />
        </el-form-item>
        <el-form-item label="处置人" required>
          <el-input v-model="reportForm.reporter" placeholder="上报 / 处置人姓名" />
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" class="bc-report-tip">
        提交后系统立即对同一采血来源仍未发出的全部成分血袋建立同源隔离；相同记录 + 相同发生时间 +
        相同症状的重复上报会自动合并为同一处置事件。
      </el-alert>
      <div class="bc-report-chain">
        <div class="bc-panel-sub" style="margin-bottom: 8px">来源链预览</div>
        <SourceChain :chain="reportTrace" />
      </div>
      <template #footer>
        <el-button @click="reportVisible = false">取消</el-button>
        <el-button type="danger" :loading="reportSubmitting" @click="submitReport">提交上报并隔离同源血袋</el-button>
      </template>
    </el-dialog>

    <!-- 质量复核对话框 -->
    <el-dialog v-model="reviewVisible" title="质量复核" width="520px">
      <el-alert type="warning" :closable="false">
        <b>排除关联</b>：只释放仍满足原检验放行、未过期且未被其他在办事件隔离的血袋；<br />
        <b>确认风险</b>：本事件隔离中的血袋全部转为报废并保留处置链（不可回退）。
      </el-alert>
      <el-form label-width="96px" style="margin-top: 14px">
        <el-form-item label="复核结论" required>
          <el-radio-group v-model="reviewForm.conclusion">
            <el-radio value="EXCLUDED">排除关联</el-radio>
            <el-radio value="CONFIRMED">确认风险</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="质量复核人" required>
          <el-input v-model="reviewForm.reviewer" placeholder="例如 质控 林岚" />
        </el-form-item>
        <el-form-item label="复核备注">
          <el-input v-model="reviewForm.note" type="textarea" :rows="2" placeholder="复核依据 / 说明（可空）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" :loading="reviewSubmitting" @click="submitReview">提交复核结论</el-button>
      </template>
    </el-dialog>
  </div>
</template>
