# u8c-agent

用友 **U8C / NC** 系 ERP 的 Agent Skill：把本机 UClient 客户端协议栈变成 `stdin JSON → stdout JSON` 的无头驱动，让 AI agent 能在真实 U8C 环境里**探测单据类型与先例字段、录入/审核/删除付款申请单、报销单等 arap 单据、创建采购合同与订单**，并能反射调用任意服务端接口。

与其他「写死字段菜谱」的方案不同，u8c-agent 的核心方法是**探测驱动**：每家企业 U8C 的自定义项、档案主键、编号规则都不同，skill 教 agent 先 `billSpec` 拉一张同类真实单据当字段模板，再 `dryRun` 用服务端校验补齐必填项——公司差异实时探测，不靠猜。

## 适用范围

| 支持 | 不支持 |
|---|---|
| U8C / NC（ServiceDispatcherServlet 协议栈） | U8+、U8 cloud、YonSuite、NCC（未验证） |
| 已装 UClient 且登录过的本机（macOS/Linux；Windows 需手动指定缓存路径） | 无 UClient 的环境 |

## 安装

```bash
npx -y skills add maruimin66-maker/u8c-agent -g --all
```

安装后 skill 位于 `skills/u8c-agent/`，引擎在 `skills/u8c-agent/agent/`（Java 源码 + 自带 ECJ 编译器，免装 JDK）。

## 快速开始

```bash
cd <安装位置>/skills/u8c-agent/agent
./compile.sh                              # 首次：编译（自动发现 UClient 缓存）

export ERP_AGENT_SERVER="http://<u8c-host>:<port>"
export ERP_AGENT_USER="<个人账号>"
export ERP_AGENT_PASSWORD="<个人密码>"

../scripts/doctor.sh                      # 自检：缓存→编译→账套→登录→profile
echo '{"action":"billTypes"}' | ./u8-agent         # 探本环境单据类型
echo '{"action":"billSpec","billType":"D3"}' | ./u8-agent  # 拉先例字段模板
```

录单（以付款单为例，dryRun 不落库先校验）：

```bash
echo '{"action":"billSave","billType":"D3","dryRun":true,
 "parent":{"djdl":"fk","dwbh":"<供应商pk>","ybje":100,"bbje":100,"djrq":"2026-01-01"},
 "children":[{"jfybje":100,"jfbbje":100,"dwbh":"<供应商pk>"}]}' | ./u8-agent
```

公司级缺省值（我方付款账户、自定义项默认值）写进 `ERP_AGENT_PROFILE` 指向的 JSON，模板见 `skills/u8c-agent/assets/profile.example.json`，逐项说明见 `references/setup.md`。

## 能力一览

- **探测层**：`accounts` / `login` / `describe` / `invoke` / `genoid` / `query` / `billTypes` / `billSpec`
- **执行层**：`billSave`（含 `dryRun`）/ `billAction`（QUERY/AUDIT/APPROVE/DELETE/UPDATE/平台动作）
- **已验证菜谱**：采购合同创建 → 参照生成订单+审核 → 付款录入全链路（`references/recipes/payflow.md`）

## 安全与合规

- 凭据只经环境变量传入，不落盘、不进日志；建议放 `~/.config/secrets.env` 或私有 env 文件。
- **仓库不含任何用友 jar**：运行时复用你本机已购许可的 UClient 缓存。使用本工具需要你方拥有合法的 U8C 使用许可与相应模块权限。
- 写操作默认到「自由态」；测试单请 `billAction DELETE` 软删。
- 每次运行占用一个并发 license（进程退出即释放）。

## English Summary

`u8c-agent` is an Agent Skill that turns a locally installed Yonyou **U8C/NC** client cache into a headless JSON-CLI driver (`stdin JSON → stdout JSON`). Agents can probe bill types and field precedents, create/audit/delete AR/AP-style bills (payment requests, expense claims), purchase contracts and orders, and reflectively invoke any server-side `nc.itf.*` service. Instead of hardcoding company-specific fields, the skill teaches a probe-first workflow (`billSpec` → `dryRun` → save → verify → cleanup). Proprietary NC jars are **not** redistributed — a licensed UClient installation is required.

## 目录结构

```
skills/u8c-agent/
├── SKILL.md                  # 探测驱动工作方法（主入口）
├── agent/                    # 引擎：Java 源码 + 启动器 + 编译器
│   ├── u8-agent              # stdin JSON -> stdout JSON 启动器
│   ├── compile.sh
│   ├── src/erp/agent/*.java
│   └── tools/                # ecj(EPL) / gson(Apache-2.0) / cfr(MIT)
├── references/               # setup / actions / probing / troubleshooting / recipes
├── scripts/doctor.sh         # 一键环境自检
├── assets/profile.example.json
└── agents/openai.yaml
```

## License

MIT（引擎代码与文档）。`tools/` 内第三方 jar 保留各自许可证：ecj(EPL-2.0)、gson(Apache-2.0)、cfr(MIT)。
