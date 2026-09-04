# JPStock — 日股分析推荐

日本股市（东证 Prime）多因子分析 + 每日推荐。

## 结构

```
engine/              选股引擎（Python 标准库，零依赖）
  fetch_universe.py     Prime 名单 + 行业（JPX，数据/prime.csv）
  fetch_prices.py       10年日线行情入库（Yahoo v8 chart → market.sqlite）
  fetch_fundamentals.py 当前估值/质量指标（irbank）
  fetch_hist_fund.py    历史年度 PER/PBR（irbank，用于回测）
  scoring.py            技术+基本面多因子打分
  recommend.py          每日推荐报告（终端/文本）
  export_daily.py       导出 data/daily.json（App 数据源）
  run_daily.py          一键每日流水线
  backtest.py           技术因子 walk-forward 回测
  backtest_value.py     PBR 价值因子横截面回测
  backtest_combo.py     价值+质量组合回测
android/             Android App（Kotlin + Compose，读 daily.json）
.github/workflows/   CI：构建 APK / 每日刷新数据
data/                运行产物（sqlite、daily.json 等）
```

## 策略

- 股票池：东证 Prime 全部（~1528 只，33 个行业）
- 因子：技术（趋势/动量/波动/回撤）60% + 基本面（估值/ROE/股息）40%
- 输出：每日 5 只，行业分散，附中英文原因
- **回测结论**：纯技术因子 10 年无超额；价值因子 2021 年东证改革后有效；组合价值+质量待全池验证（见 engine 内回测脚本）
- 免责声明：量化规则信号，非投资建议

## 本地运行

```bash
cd engine
python3 run_daily.py          # 完整流水线 → data/daily.json
python3 recommend.py          # 终端报告
python3 backtest_combo.py     # 组合策略回测
```

## CI

- `build-apk.yml`：push 后构建 debug APK（artifact 下载）
- `daily-data.yml`：工作日收盘后自动刷新 daily.json 并提交
