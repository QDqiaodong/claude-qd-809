<script setup>
import { fmtDate, fmtDateTime } from '../api'

/** 来源链：发血记录 → 供血血袋 → 制备批次 → 采血记录（献血者）→ 检验标本 */
defineProps({
  chain: { type: Object, default: null }
})

function splits(batch) {
  if (!batch) return ''
  const parts = []
  if (batch.rbcBags) parts.push(`红细胞 ${batch.rbcBags}袋 ${batch.rbcVolumeMl}ml`)
  if (batch.plasmaBags) parts.push(`血浆 ${batch.plasmaBags}袋 ${batch.plasmaVolumeMl}ml`)
  if (batch.plateletBags) parts.push(`血小板 ${batch.plateletBags}袋 ${batch.plateletVolumeMl}ml`)
  if (batch.cryoBags) parts.push(`冷沉淀 ${batch.cryoBags}袋 ${batch.cryoVolumeMl}ml`)
  return parts.join(' / ') || '--'
}

function resultText(r) {
  return r === 'NEGATIVE' ? '阴性' : r === 'POSITIVE' ? '阳性' : '--'
}
</script>

<template>
  <div class="bc-chain" v-if="chain">
    <!-- ① 发血记录 -->
    <div class="bc-chain-node n1">
      <div class="bc-chain-title">① 发血记录</div>
      <template v-if="chain.match">
        <div class="bc-chain-row"><span class="k">配血单号</span><span class="v">{{ chain.match.matchNo }}</span></div>
        <div class="bc-chain-row"><span class="k">医院</span><span class="v">{{ chain.match.hospital }}</span></div>
        <div class="bc-chain-row"><span class="k">受血者</span><span class="v">{{ chain.match.recipient }}</span></div>
        <div class="bc-chain-row">
          <span class="k">成分/量</span>
          <span class="v">{{ chain.match.componentName }} · {{ chain.match.unitsU }} U</span>
        </div>
        <div class="bc-chain-row"><span class="k">发血时间</span><span class="v">{{ fmtDateTime(chain.match.issueTime) }}</span></div>
        <div class="bc-chain-row"><span class="k">发血人</span><span class="v">{{ chain.match.issueOperator || '--' }}</span></div>
      </template>
      <div v-else class="bc-chain-missing">无发血记录</div>
    </div>

    <!-- ② 供血血袋 -->
    <div class="bc-chain-node n2" :class="{ missing: !chain.bag }">
      <div class="bc-chain-title">② 供血血袋</div>
      <template v-if="chain.bag">
        <div class="bc-chain-row"><span class="k">袋号</span><span class="v">{{ chain.bag.bagNo }}</span></div>
        <div class="bc-chain-row">
          <span class="k">血型/成分</span>
          <span class="v"><el-tag size="small" type="danger" effect="dark">{{ chain.bag.bloodType }}</el-tag> {{ chain.bag.componentName }}</span>
        </div>
        <div class="bc-chain-row"><span class="k">容量</span><span class="v">{{ chain.bag.volumeMl }} ml</span></div>
        <div class="bc-chain-row"><span class="k">失效日期</span><span class="v">{{ fmtDate(chain.bag.expiryDate) }}</span></div>
        <div class="bc-chain-row">
          <span class="k">当前状态</span>
          <span class="v">
            <el-tag size="small" :type="chain.bag.status === 'AVAILABLE' ? 'success' : chain.bag.status === 'ISOLATED' ? 'warning' : 'info'" effect="dark">
              {{ chain.bag.statusName }}
            </el-tag>
          </span>
        </div>
        <div class="bc-chain-row"><span class="k">献血者</span><span class="v">{{ chain.bag.donorName || '--' }}</span></div>
      </template>
      <div v-else class="bc-chain-missing">无血袋记录</div>
    </div>

    <!-- ③ 制备批次 -->
    <div class="bc-chain-node n3" :class="{ missing: !chain.batch }">
      <div class="bc-chain-title">③ 制备批次</div>
      <template v-if="chain.batch">
        <div class="bc-chain-row"><span class="k">批号</span><span class="v">{{ chain.batch.batchNo }}</span></div>
        <div class="bc-chain-row"><span class="k">分离成果</span><span class="v">{{ splits(chain.batch) }}</span></div>
        <div class="bc-chain-row"><span class="k">制备人</span><span class="v">{{ chain.batch.operator || '--' }}</span></div>
        <div class="bc-chain-row"><span class="k">制备时间</span><span class="v">{{ fmtDateTime(chain.batch.preparedTime) }}</span></div>
      </template>
      <div v-else class="bc-chain-missing">无制备批次（手工入库血袋）</div>
    </div>

    <!-- ④ 采血记录 -->
    <div class="bc-chain-node n4" :class="{ missing: !chain.collection }">
      <div class="bc-chain-title">④ 采血记录</div>
      <template v-if="chain.collection">
        <div class="bc-chain-row"><span class="k">采血单号</span><span class="v">{{ chain.collection.collectNo }}</span></div>
        <div class="bc-chain-row">
          <span class="k">献血者</span>
          <span class="v">{{ chain.collection.donorName || '--' }}（{{ chain.collection.donorBloodType || '--' }} · {{ chain.collection.donorNo || '--' }}）</span>
        </div>
        <div class="bc-chain-row"><span class="k">采血量</span><span class="v">{{ chain.collection.volumeMl }} ml · 采血位 {{ chain.collection.stationCode }}</span></div>
        <div class="bc-chain-row"><span class="k">采血日期</span><span class="v">{{ fmtDate(chain.collection.collectDate) }}</span></div>
        <div class="bc-chain-row"><span class="k">采血护士</span><span class="v">{{ chain.collection.nurse || '--' }}</span></div>
      </template>
      <div v-else class="bc-chain-missing">无采血记录（手工入库血袋）</div>
    </div>

    <!-- ⑤ 检验标本 -->
    <div class="bc-chain-node n5" :class="{ missing: !chain.sample }">
      <div class="bc-chain-title">⑤ 检验标本</div>
      <template v-if="chain.sample">
        <div class="bc-chain-row">
          <span class="k">条码</span>
          <span class="v">{{ chain.sample.barcode }}
            <el-tag size="small" :type="chain.sample.status === 'RELEASED' ? 'success' : 'warning'">{{ chain.sample.statusName }}</el-tag>
          </span>
        </div>
        <div class="bc-chain-row">
          <span class="k">ALT / Hb</span>
          <span class="v">{{ chain.sample.altValue ?? '--' }} U/L · {{ chain.sample.hbValue ?? '--' }} g/L</span>
        </div>
        <div class="bc-chain-row">
          <span class="k">四项</span>
          <span class="v">
            HBV {{ resultText(chain.sample.hbvResult) }} · HCV {{ resultText(chain.sample.hcvResult) }}<br />
            HIV {{ resultText(chain.sample.hivResult) }} · 梅毒 {{ resultText(chain.sample.syphilisResult) }}
          </span>
        </div>
        <div class="bc-chain-row"><span class="k">复核人</span><span class="v">{{ chain.sample.reviewer || '--' }}</span></div>
        <div class="bc-chain-row"><span class="k">放行时间</span><span class="v">{{ fmtDateTime(chain.sample.releasedAt) }}</span></div>
      </template>
      <div v-else class="bc-chain-missing">无检验标本（手工入库血袋）</div>
    </div>
  </div>
  <div v-else class="bc-empty">来源链加载中…</div>
</template>
