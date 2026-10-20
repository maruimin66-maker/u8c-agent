# 范例链路：采购合同 → 采购订单 → 付款录入

一条在真实环境验证过的写入链路，展示「菜谱」层与探测层的配合。每一步幂等可重跑。

> 该链路的专用 action（`createPurchaseContract`/`orderFromContract`/`payEntry`）是便利封装；
> 同样的效果也能用通用 `billSave` + `invoke` 拼出——菜谱存在的意义是把反编译验证过的字段语义固化下来。

## 1. 环境

```bash
cd <skill目录>/agent && ./compile.sh
export ERP_AGENT_SERVER ERP_AGENT_USER ERP_AGENT_PASSWORD ERP_AGENT_PROFILE
```

## 2. 采购合同（尚无生效合同时）

```bash
echo '{"action":"createPurchaseContract","billType":"Z2",
 "head":{"pk_corp":"<公司pk>","pk_ct_type":"<合同类型pk>","ct_name":"<合同名>",
  "custid":"<客商pk>","currid":"<币种pk>","currrate":1,
  "subscribedate":"<签订日>","valdate":"<生效日>","invallidate":"2099-01-01",
  "depid":"<部门pk>","personnelid":"<人员pk>"},
 "items":[{"invid":"<存货pk>","measid":"<计量单位pk>","amount":1,"taxration":6,
   "oritaxprice":<含税单价>,"oritaxsummny":<价税合计>,"natitaxsummny":<本币价税合计>,
   "orisum":<无税金额>,"natisum":<无税>,"oriprice":<无税单价>,"natiprice":<无税>,
   "oritaxmny":<税额>,"natitaxmny":<税额>}]}' | ./u8-agent
```

- **参照先例取值**：`query` 查 `ct_manage` 先例 → `IContractBaseQuery.queryAllHeadData+queryAllBodyData` 取全量字段照抄结构。
- **不传 `ct_code` → 服务端编号规则生成正式编号**；显式传会按给定值落库，编号格式错了成脏数据（须删除重来）。
- 金额字段全给，服务端不再反算。
- 合同类型→`pk_ct_type`：`query` `ct_type`；存货→`bd_invmandoc`/`bd_invbasdoc`；客商→`bd_cumandoc`→`bd_cubasdoc`。

## 3. 订单（参照合同生成 + 审核 + 预付款草稿）

```bash
echo '{"action":"orderFromContract","ct_code":"<合同号>",
 "warehouse":"<仓库名>","approve":true,"prepay":{"amount":<预付款金额>}}' | ./u8-agent
```

- 合同须 `ctflag=2`（已生效）；返回 `orderPk`（订单头 pk）、`orderCode`、`payBillCode`。
- 幂等：重跑不会重复建单；自由态缺仓库旧单自动 DISCARD 重建。
- `head` 里可传 `vdef*` 覆盖项（依 profile `order.propagateHeadFields` 透传到行）。

## 4. 付款录入保存（订单→付款单）

```bash
echo '{"action":"payEntry","orderPk":"<订单头pk>","corp":"<公司pk>"}' | ./u8-agent
```

复刻人工「付款录入」卡片链路：
- `CHG*` 客户端转换生成付款单 VO（`queryBillMap` 解析目标类型，D3→父类型 F3 脚本回退）；
- 银行字段：`bfyhzh`=我方付款账户 pk、`dfyhzh`=对方收款账户、`fkyhmc`=对方开户行**全称**、`skyhmc`=我方开户行、`bankacc_fk`=对方账号、`pj_jsfs`=结算方式（集团层 `bd_balatype`）；
- `processAction("SAVE")` 保存；联动 `auditArapBill` 审核推式草稿（`spzt=1`）；
- 防重：已有 `dr=0` 的同源付款单跳过。

## 5. 验证与清理

```bash
echo '{"action":"billAction","op":"QUERY","djbh":"<付款单号>"}' | ./u8-agent   # 回查
echo '{"action":"billAction","op":"DELETE","vouchid":"<pk>"}' | ./u8-agent    # 测试单软删
```

## 注意

- 保存后若有下游集成（审批系统、推送平台），那是**本企业自己的链路**，引擎只负责 U8C 侧写库正确；用 `billAction QUERY`/`query` 查 `spzt`/`djzt` 判状态。
- 删错不联动：U8 软删不会反向同步外部系统已生成的数据，外部侧自行处理。
