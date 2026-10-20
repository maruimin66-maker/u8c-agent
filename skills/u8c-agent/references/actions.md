# Action 参数参考

统一约定：`stdin` 一行 JSON → `stdout` 一行 `{"ok":true,"data":...}` 或 `{"ok":false,"error":"...","errorType":"..."}`。

## 探测层

### accounts（匿名）

```json
{"action":"accounts"}
```
返回 `[{accountCode, accountName, dataSource}]`。不需要 `ERP_AGENT_USER`；只需 `ERP_AGENT_SERVER` 可达。

### login

```json
{"action":"login"}
```
返回 `{userId, userCode, userName, accountId, corpPk, corpName, tokenLen}`。占用一个并发 license（进程退出即释放）。

### describe（离线可用）

```json
{"action":"describe","service":"nc.itf.uap.sf.ILogin3Service"}
```
返回方法签名数组。纯本地反射，不联网——适合先确认接口是否存在、参数类型是什么。

### invoke / invokeAnon

```json
{"action":"invoke","service":"nc.itf.X","method":"m","argTypes":["java.lang.String"],"args":["v"]}
```
- `argTypes` 写 Java 全限定类型名（`java.lang.String`、`int`、`[Lnc.vo.po.OrderVO;`、`nc.vo.pub.AggregatedValueObject`）。
- `args` 按 JSON→Java 转换：数值→UFDouble、`yyyy-MM-dd`→UFDate、对象→VO/bean（`_class` 可指定具体类）。
- `invokeAnon` 跳过登录（仅少数匿名接口可用）。

### genoid

```json
{"action":"genoid","pkCorp":"<公司pk>","count":3}
```

### query

```json
{"action":"query","table":"bd_measdoc","cols":["pk_measdoc","measname"],
 "where":"measname='次'","maxRows":50}
```
- `table`/`cols` 标识符校验（防注入）；`where` 为 SQL 原样拼接（可 `order by`）。
- 返回 `{cols, rows, rowCount, truncated}`。

### billTypes

```json
{"action":"billTypes","where":"可选附加条件","maxRows":300}
```
按候选元数据表（bd_billtype / pub_billtype / sm_billtype × 两种列名约定）逐个探测，首个可查表返回 `{metaTable, cols, rows}`。全部失败时返回已试清单，改用 `describe`/`query` 自行定位。

### billSpec —— 先例字段模板

```json
{"action":"billSpec","billType":"D3"}            // arap：该类型 dr=0 最新一张全量 VO
{"action":"billSpec","djbh":"FK..."}             // 指定单据号
{"action":"billSpec","billType":"D3","corp":"1002"}
```
返回 `{kind:"arap", vouchid, djbh, djlxbm, djdl, spzt, precedents, vo}`。`vo` 含 `_class`/`parent`/`children` 全部字段名与先例值——`children` 是数组，通常照抄第一条改业务值。

```json
{"action":"billSpec","table":"po_order","cols":["corderid","vordercode","pk_corp"],
 "where":"1=1 order by dorderdate desc",
 "bodyTable":"po_order_b","bodyFk":"corderid","headPk":"corderid","bodyCols":[...]}
```
通用表路径：返回 `{kind:"table", head:{cols,rows}, body:{...}}`。

## 执行层

### billSave

```json
{"action":"billSave","billType":"D3",
 "parent":{"djdl":"fk","dwbh":"<pk>","ybje":100,"bbje":100,"djrq":"2026-01-01"},
 "children":[{"jfybje":100,"jfbbje":100,"dwbh":"<pk>"}],
 "dryRun":true}
```

| 参数 | 说明 |
|---|---|
| `billType` | 必填，单据类型码（D3/B7 等，用于平台动作与编号） |
| `voClass` | 默认 `nc.vo.ep.dj.DJZBVO`；其他聚合 VO 传全限定类名 |
| `corp` | 公司 pk；缺省 `parent.pk_corp` → `profile.corp` → 登录公司 |
| `pkMap` | `{"parent":"vouchid","child":"djvbid","fk":"vouchid"}`；arap 内置默认，其他 VO 类需显式给 |
| `parent`/`children` | 字段 JSON；profile `bill.<billType>.*Defaults` 与 `defaults` 作缺省合并（显式 > spec.defaults > profile） |
| `saveAction` | 默认 `SAVE` |
| `dryRun` | `true` 时只构造 VO + `checkWorkitemOnSave` 校验，不落库 |

行为：
- 自动补 `vouchid`/`djvbid` OID、`dr=0`、`status=NEW`、子表外键回指。
- 返回 `vo`（类型转换后回显——**核对字段是否真的写进 VO**，写错字段名会静默丢）；`saveRet`；arap 时附 `bills` 回查与 `verified`。

### billAction / payAction

```json
{"action":"billAction","op":"QUERY","djbh":"FK..."}
{"action":"billAction","op":"AUDIT","vouchid":"<pk>"}
{"action":"billAction","op":"EDIT","vouchid":"<pk>","fields":{...},"itemFields":{...}}
```

- `vouchid`/`djbh` 二选一；`billType` 缺省按 `arap_djzb.djlxbm` 反查。
- `op`：`QUERY`（返回 head 行+全量 VO）、`INSPECT`、`DELETE`（软删）、`UPDATE`、`AUDIT/UNAUDIT/AUDITONE/UNAUDITONE`（卡片级审核）、`APPROVE*`（UI 批审链路）、其他按平台动作（`checkWorkitemOnSave`→`processAction`）。
- **UPDATE/EDIT 不写表体**；表体写错只能 DELETE 重建。

### 采购链路菜谱

```json
{"action":"createPurchaseOrder","head":{"pk_corp":"...","cbiztype":"...","cvendorbaseid":"...",
  "ccurrencytypeid":"...","cdeptid":"...","cpurorganization":"..."},
 "items":[{"cinventoryid":"...","cbaseid":"...","nordernum":1,"nprice":100,
   "nmoney":100,"noriginalcurmny":100,"ntaxrate":0,"ccurrencytypeid":"...","cwarehouseid":"..."}],
 "vordercode":"可选","codePrefix":"PO"}
```
```json
{"action":"createPurchaseContract","billType":"Z2",
 "head":{"pk_corp":"...","pk_ct_type":"...","ct_name":"...","custid":"...","currid":"...",
   "currrate":1,"subscribedate":"...","valdate":"...","invallidate":"...","depid":"...","personnelid":"..."},
 "items":[{"invid":"...","measid":"...","amount":1,"taxration":6,"oritaxprice":100,
   "oritaxsummny":100,"natitaxsummny":100,"orisum":94.34,"natisum":94.34,
   "oriprice":94.34,"natiprice":94.34,"oritaxmny":5.66,"natitaxmny":5.66}]}
```
- 不传 `ct_code` → 服务端编号规则生成正式编号；显式传则按给定值落库。
- 删除自由态合同：`invoke` → `nc.itf.ct.pub.IContractBaseQuery.queryAllHeadData` + `queryAllBodyData` 取 ManageVO → `nc.itf.ct.pub.IContractBaseWrite.delete(ManageVO)`。

```json
{"action":"orderFromContract","ct_code":"<合同号>","head":{...覆盖},
 "warehouse":"<仓库名>","approve":true,"prepay":{"amount":5000}}
```
幂等：已存在有效订单则复用；自由态缺仓库旧单自动废弃重建；返回 `{orderPk, orderCode, audited, payVouchid, payBillCode}`。

```json
{"action":"payEntry","orderPk":"<订单头pk>","corp":"<公司pk>","amount":0}
```
复刻「预付款→付款录入→保存」链路：订单 VO→可预付校验→按比例改写行金额→单据映射→CHG 客户端转换→银行字段（`bfyhzh`/`dfyhzh`/`fkyhmc`/`skyhmc`/`bankacc_fk`/`pj_jsfs`）→SAVE→联动审核推式草稿。`amount<=0` 按可预付全额。

```json
{"action":"discardPurchaseOrder","orderPk":"<pk>"}
```
仅自由态(0)/审核失败(4)可废弃。

## 输出与日志

- 结果单行 JSON；框架日志全走 `stderr`（调用方只读 stdout 即可）。
- `saveRet`/`ret` 是服务端原始返回序列化（含警告信息结构），判成败以回查为准。
