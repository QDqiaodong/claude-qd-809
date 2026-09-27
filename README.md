# 血站 · 采血供血与血液库存管理

以「血型 × 成分」二维库存和「采血 → 检验 → 制备 → 库存 → 发血」闭环为核心的血站管理平台。

## 技术栈

- 后端：Spring Boot 3.3.0 + Java 17 + Spring Data JPA(Hibernate 6.5) + MySQL 8 + Redis
- 前端：Vue 3 + Element Plus + Vite
- 端口：前端 8289 / 后端 8389 / MySQL 3589 / Redis 6589

## 启动

```bash
./start.sh          # 首次或镜像不存在时自动构建
./stop.sh           # 停止
./resume.sh         # 恢复
./clean.sh          # 收摊（docker compose down -v）
```

## 五个模块

| 模块 | 说明 |
| --- | --- |
| 献血者与采血 | 采血位状态盘（12 个位圆环排布）、采血登记、完成采血 |
| 标本检验 | 待检 / 检测中 / 已放行 三条流转轨道，六项检验（ALT、血红蛋白、HBV、HCV、HIV、梅毒） |
| 成分制备 | 全血分离出红细胞悬液 / 血浆 / 血小板 / 冷沉淀 的袋数与容量 |
| 血液库存 | 8 血型 × 4 成分二维矩阵、预警线、近效期、报废记录 |
| 用血申请与发血 | 医院申请、交叉配血三态判定、发血出库与血袋回收核对 |

## 数据访问路线：JPA 领域事件驱动跨聚合联动

实体用 `@Transient List<Object> events` + `@DomainEvents` + `@AfterDomainEventPublication` 登记事件，
监听器用 `@TransactionalEventListener(phase = AFTER_COMMIT)` 落副作用：

| 领域事件 | 触发点 | 副作用 |
| --- | --- | --- |
| `CollectionCompletedEvent` | 采血完成 | 自动生成检验标本，进入待检轨道 |
| `SampleReleasedEvent` | 标本检验放行 | 自动生成成分制备批次 + 生成血袋 + 计入库存 |
| `BloodExpiredEvent` | 过期扫描判废 | 自动生成报废记录 + 从可用库存扣减 |
| `BloodIssuedEvent` | 发血出库 | 扣减血型×成分库存 + 记录发血流水 + 回写用血申请 |

手写 JPQL 汇总：

- `BloodInventoryRepository.summaryByBloodTypeAndComponent()` 按血型 × 成分汇总库存
- `CrossMatchRepository.summaryByHospital()` 按医院汇总发血量
- `PreparationBatchRepository.summaryByBloodType()` 按血型汇总制备成果

## 业务红线（抛 BizException）

- 两次全血献血间隔不足 6 个月不允许采血（返回下次可献日期）
- 采血量超过「体重 kg × 8 ml」核定上限不允许采血
- 检验未放行的血液不允许发血
- 库存不足时发血给出缺口 U 数
- 配血不相合绝对不允许出库；可疑需复核
- 过期血液不允许出库

## 前端四种操作范式

1. 采血位状态盘：CSS 绝对定位摆成圆环的点位，点圆牌弹窗登记采血
2. 血型 × 成分库存矩阵：8 × 4 格子按预警 / 近效期染色，点格子右侧滑出袋号明细并可出入库
3. 标本检验流转轨道：HTML5 原生拖拽，落入「已放行」需选复核人并即时触发成分制备
4. 交叉配血对照卡：左右对照 + 中间三态按钮组，不相合时出库按钮永久禁用并显示红条
