# bank_forecast frontend

Vue 管理台。

## 启动

```bash
pnpm install
VITE_BACKEND_BASE_URL=http://localhost:8080 pnpm dev
```

默认地址：`http://localhost:5173`

## 当前功能

- 登录资金工作台。
- 查看数据库驱动的驾驶舱指标和银行账户。
- 上传 CSV 银行流水并查看导入结果。
- 查询已导入的银行流水明细。

前端只在浏览器本地保存访问令牌和当前用户信息，业务数据保存在后端数据库。
