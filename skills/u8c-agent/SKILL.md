---
name: u8c-agent
description: 用友 U8C/NC 系 ERP 无头驱动。复用本机 UClient 协议栈，可探测单据类型与先例字段、录入/审核/删除付款申请单、报销单等 arap 单据与采购合同订单，并能反射调用任意服务端接口。需本机已装 UClient 且登录过。触发词：U8C、U8 录单、付款单、付款申请单、报销单、采购合同、采购订单、U8 接口调用、NC 单据。
---

# u8c-agent：U8C 单据与接口无头驱动

把「给某个确定单据类型录单」做成方法而不是菜谱：**探测环境 → 先例建模板 → 构造字段 → 试存校验 → 回查验证 → 清理**，所有公司差异（自定义项、档案 pk、编号规则）都从目标环境实时获取，不靠猜。

## 适用边界

- 仅 **U8C / NC 系**（ServiceDispatcherServlet 协议栈）。不适用 U8+、U8 cloud、YonSuite。
- 本机需已装 UClient 且成功登录过至少一次（存在 `~/UClient` 缓存）；非 macOS/Linux 路径需手动设 `ERP_AGENT_CODE_DIR`。
- 每次运行占一个并发 license；凭据只走环境变量。
- 写操作默认只到「自由态」；审核类动作按单据类型显式触发。

## 第 0 步：环境自检（每次新环境必做）

```bash
cd "$(dirname "$0")/agent"   # 即 <skill目录>/agent
./compile.sh                  # 首次或改码后；需 UClient 缓存
export ERP_AGENT_SERVER="http://<u8c-host>:<port>"
export ERP_AGENT_USER ERP_AGENT_PASSWORD   # 个人账号，凭据不落盘
echo '{"action":"accounts"}' | ./u8-agent   # 匿名冒烟：网络+classpath
echo '{"action":"login"}'    | ./u8-agent   # 登录冒烟：凭据+账套
```

也可用 `../scripts/doctor.sh` 一键自检（编译→accounts→可选 login→profile 检查）。

## 工作方法（六步）

### 1. 探测环境

不知道目标单据类型编码时先探，不要假设：

```bash
echo '{"action":"billTypes"}' | ./u8-agent                    # 列本环境单据类型
echo '{"action":"query","table":"bd_measdoc","cols":["pk_measdoc","measname"],"where":"1=1"}' | ./u8-agent
echo '{"action":"describe","service":"nc.itf.uap.sf.ILogin3Service"}' | ./u8-agent   # 离线可用
```

`describe` + `invoke` 是逃生门：本环境任何服务端接口都能先列签名再反射调用，详见 `references/probing.md`。

### 2. 先例建模板（核心步骤）

**该类型至少已存在一张真实单据时，先例就是字段标准答案**——自定义项、部门、模板默认值全部现形：

```bash
echo '{"action":"billSpec","billType":"D3"}' | ./u8-agent
# 或指定某张单：{"action":"billSpec","djbh":"FK..."}
# 或非 arap 表：{"action":"billSpec","table":"po_order","cols":[...],"where":"...","bodyTable":"po_order_b","bodyFk":"corderid","headPk":"corderid"}
```

返回 `vo.parent`/`vo.children` 是带全部字段名的真实 JSON。新业务值替换业务字段；档案类字段（供应商、部门、币种）从先例继承或在 `references/probing.md` 查对应 `bd_*` 表解析。

### 3. 构造字段 + profile 缺省

公司级缺省值（我方付款账户、自定义项默认值）放 profile（`ERP_AGENT_PROFILE` 指向，模板见 `assets/profile.example.json`）。缺 profile 不会阻断探测与通用录入，只有依赖缺省值的链路（如 payEntry 银行字段）会报「该配哪项」。

注意：**写错字段名会静默无效**（setAttributeValue 对未知属性不报错）。对照 billSpec 先例字段名写，或用返回的 `vo` 回显核对。

### 4. 试存校验（dryRun）

```bash
echo '{"action":"billSave","billType":"D3","dryRun":true,
 "parent":{"djdl":"fk","dwbh":"<供应商pk>","ybje":100,"bbje":100,"djrq":"2026-01-01"},
 "children":[{"jfybje":100,"jfbbje":100,"dwbh":"<供应商pk>"}]}' | ./u8-agent
```

`dryRun` 走 `checkWorkitemOnSave` 服务端校验但**不落库**——缺必填字段会以校验错误返回，按报错补字段再试，直到通过。

### 5. 保存与回查验证

去掉 `dryRun` 实存，返回 `bills` 回查结果（djbh/spzt/djzt/dr）。单据号经服务端编号规则生成；不要显式传编号让规则失效（除非明确要固定编号）。

### 6. 测试单清理

验证用的测试单**必须软删**（dr=1，不占额度、可反查）：

```bash
echo '{"action":"billAction","op":"DELETE","vouchid":"<pk>"}' | ./u8-agent
```

## action 速查

| 类别 | action | 一句话 |
|---|---|---|
| 探测 | `accounts`/`login`/`describe`/`invoke`/`genoid` | 账套/登录/接口签名/任意调用/取 OID |
| 探测 | `query`/`billTypes`/`billSpec` | 任意表查询/单据类型/先例模板 |
| 执行 | `billSave`/`billAction` | 通用录单(dryRun)/单据动作 |
| 菜谱 | `createPurchaseOrder`/`createPurchaseContract`/`orderFromContract`/`discardPurchaseOrder`/`payEntry`/`payAction` | 已验证的采购合同→订单→付款链路 |

完整参数见 `references/actions.md`。

## 不变量

- 每步幂等可重跑；写错字段的单**只能软删重建**（UPDATE/EDIT 不写表体）。
- 金额字段宁多勿缺：head `ybje/bbje/fbje`、行 `jf*/df*` 全给，不依赖服务端反算。
- 不传单据编号 → 走服务端编号规则；显式传编号按给定值落库（错误格式成脏数据）。
- 软删用 `billAction DELETE`（dr=1），不做物理删除。
- 凭据、内网地址、档案 pk 只进环境变量与本地 profile，不写进会话产物。

## references 索引

- `references/setup.md` — 前置环境、环境变量、profile 全字段说明
- `references/actions.md` — 全部 action 的 JSON 参数与返回
- `references/probing.md` — 探测手册：常用表清单、字段语义、describe/invoke 用法
- `references/troubleshooting.md` — 排错速查
- `references/recipes/payflow.md` — 已验证范例：采购合同→订单→付款录入全链路

## 停止条件

- dryRun/SAVE 返回的校验错误连续两次同一字段 → 停下来核对先例与档案映射，不要盲试。
- 要执行的动作影响已审核/已生效单据 → 先向用户确认业务后果。
- 服务端接口与预期不符（版本差异）→ 用 `describe` 核对签名，仍不符则报告。
