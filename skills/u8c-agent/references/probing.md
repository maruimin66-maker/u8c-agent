# 探测手册

目标：在一个**完全陌生的 U8C 环境**里，不靠任何先验配置摸清「这个单据类型要填什么」。

## 探测顺序

```
billTypes        → 知道有哪些单据类型（djlxbm）
billSpec         → 拉该类型一张真实单据的全字段 JSON（先例即模板）
query            → 查档案表把显示值/编码解析成 pk
describe+invoke  → 元数据表/文档都没覆盖时，直接反射调服务端接口
billSave dryRun  → 服务端校验当免费字段老师：缺什么报错补什么
```

## arap 单据（付款/报销/借款/应付）字段语义

- 存储：头 `arap_djzb`、体 `arap_djfb`；VO 同为 `nc.vo.ep.dj.DJZBVO`（`DJZBHeaderVO`+`DJZBItemVO`）。
- 单据大类 `djdl`：`fk` 付款 / `yf` 应付 / `bx` 报销 / `jk` 借款（以本环境实际类型为准，billSpec 看先例）。
- 单据类型 `djlxbm`：D3、D1、B7 等——**每家自定义，必须用 billTypes/billSpec 探，不要假设**。
- 状态：`spzt` 审批态、`djzt` 单据状态、`dr` 删除标记（1=软删）。
- 金额：**表头** `ybje`（原币）/`bbje`（本币）/`fbje`；**表体** `jfybje/jfbbje`（借方原币/本币）、`dfybje/dfbbje`（贷方）。写错层级会静默无效——用返回 `vo` 回显核对。
- 银行字段（表体 `arap_djfb` 为主）：

| 字段 | 含义 | 取值来源 |
|---|---|---|
| `bfyhzh` | 我方付款账户 | `bd_bankaccbas.pk`（本公司的） |
| `dfyhzh` | 对方收款账户 | 对方的 `bd_bankaccbas.pk`（或按账号解析） |
| `bankacc_fk` | 对方账号显示值 | `bd_bankaccbas.bankacc` |
| `fkyhmc` | 对方开户行全称 | 对方 `bd_bankdoc.bankdocname`（下游常按此反查，**必须全称**） |
| `skyhmc` | 我方开户行名 | 我方 `bd_bankdoc.bankdocname` |
| `pk_bankdoc` | 对方开户行 pk | `bd_bankaccbas.pk_bankdoc` |
| `pj_jsfs` | 结算方式 | `bd_balatype.pk`（常挂集团层 `pk_corp=0001`，别按公司过滤） |
| `zjldw` | 资金计量单位 | `bd_measdoc.pk`（按名查） |

- 自定义项 `zyx1`~`zyx30`（头/体都有）：**纯公司自定义**，先例是唯一权威。
- 供应商/客户链路：`cumandoc.pk_cubasdoc` → `bd_custbank`（账号，`defflag='Y'` 默认户）→ `bd_bankaccbas`/`bd_bankdoc`。

## 常用档案表（bd_*）

| 表 | 用途 | 常用列 |
|---|---|---|
| `bd_cumandoc` | 客商管理档案（公司层） | pk_cumandoc, pk_cubasdoc, custcode, custname |
| `bd_cubasdoc` | 客商基本档案（集团层） | pk_cubasdoc, custcode, custname |
| `bd_custbank` | 客商银行账户 | pk_custbank, pk_cubasdoc, pk_accbank, accname, account, defflag |
| `bd_bankaccbas` | 银行账户档案 | pk_bankaccbas, accountcode, accountname, bankacc, pk_bankdoc, pk_corp |
| `bd_bankdoc` | 开户行档案 | pk_bankdoc, bankdoccode, bankdocname |
| `bd_balatype` | 结算方式 | pk_balatype, balancode, balanname（查集团层 0001） |
| `bd_measdoc` | 计量单位 | pk_measdoc, meascode, measname |
| `bd_invmandoc` | 存货管理档案（公司层） | pk_invmandoc, pk_invbasdoc, invcode, invname |
| `bd_invbasdoc` | 存货基本档案（集团层） | pk_invbasdoc, invcode, invname |
| `bd_stordoc` | 仓库 | pk_stordoc, storcode, storname, pk_corp |
| `bd_deptdoc` | 部门 | pk_deptdoc, deptcode, deptname |
| `bd_psndoc` | 人员 | pk_psndoc, psncode, psnname |
| `bd_currtype` | 币种 | pk_currtype, currtypecode, currtypename |
| `bd_busitype` | 业务类型 | pk_busitype, busicode, businame, pk_corp |
| `bd_defdoc` / `bd_defdoclist` | 自定义项档案值 | pk_defdoclist, defcode, defname, pk_defdoc |
| `ct_type` / `ct_manage`(`_b`) | 合同类型 / 合同头(体) | pk_ct_type / pk_ct_manage, ct_code, ctflag |
| `po_order`(`_b`) | 采购订单头(体) | corderid, vordercode, forderstatus |
| `arap_djzb`(`_b` 用 `arap_djfb`) | 单据头(体) | vouchid, djbh, djlxbm, djdl, spzt, djzt, dr, ts |

## describe / invoke 逃生门

`billTypes` 失败、文档没覆盖的接口、需要平台动作的模块——先 `describe` 列签名再 `invoke`：

```bash
# 例：枚举某接口方法
{"action":"describe","service":"nc.itf.arap.pub.IArapBillPublic"}
# 例：调任意查询服务（等价于 query 动作的底层）
{"action":"invoke","service":"nc.itf.pu.pub.IPub","method":"queryResultsFromAnyTable",
 "argTypes":["java.lang.String","[Ljava.lang.String;","java.lang.String"],
 "args":["bd_measdoc",["pk_measdoc","measname"],"1=1"]}
```

- VO 参数传 JSON 对象即可：`{"_class":"nc.vo.po.OrderVO","parent":{...},"children":[...]}`，引擎自动构造（`JsonCodec.toAggVo`：bean setter 优先，未知属性静默跳过）。
- 数组参数类型写 `[L全限定名;`（如 `[Lnc.vo.po.OrderVO;`）。
- 不知道接口名时：在 NCCACHE jar 里 `jar tf`/`grep` 接口类名，或问熟悉系统的同事——`describe` 只能验证存在性，不能搜索。

## 常见坑

- 档案有**公司层/集团层**之分：`bd_cumandoc`/`bd_invmandoc` 按 `pk_corp` 过滤；`bd_cubasdoc`/`bd_balatype` 多在集团 `0001`。
- `ts` 是时间戳列（`order by ts desc` 取最新先例）。
- 单据类型码（D3/Z2/21）与单据大类（fk/yf）、交易类型是不同维度，别混用。
- 校验报错提到的字段名，直接回 billSpec 先例里找它的取值形态。
