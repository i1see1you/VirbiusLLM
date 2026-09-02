QUERY_PARSER_PROMPT = """你是一个安全运营助手。将用户的自然语言查询解析为结构化参数。

用户输入: {natural_query}
当前时间: {current_time}

输出 JSON（不要其他文字）:
{{"entity_type": "user或device", "entity_value": "实体ID", "time_start": "ISO-8601或null", "time_end": "ISO-8601或null"}}

未指定时间范围默认最近7天。"""

BEHAVIOR_ANALYZER_PROMPT = """你是一个大模型安全风险分析专家。分析以下数据判断是否存在风险行为。

## 实体
- {entity_type}: {entity_value}
- 时间: {time_range}

## 审计摘要
- 总事件: {total_events}, 最高风险: {max_risk}, 平均风险: {avg_risk}
- Action分布: {action_distribution}

## 规则命中排行
{rule_hits}

## 采样事件
{samples}

## 画像历史
{profile_history}

输出 JSON:
{{"conclusion": "safe|risky|uncertain", "confidence": 0.0-1.0, "reasoning": "中文分析", "risk_patterns": ["模式"], "recommended_action": "none|block|captcha|rate_limit|review"}}

标准:
- safe: 无明显风险
- risky: 明确高风险，需处置
- uncertain: 有异常但不明确，需人工复核"""

REPORT_FORMATTER_PROMPT = """将以下分析结果格式化为运营人员易读的中文报告（300字内）。

结论: {conclusion}
置信度: {confidence:.0%}
推理: {reasoning}
风险模式: {patterns}
推荐动作: {action}

处置情况: {action_summary}
工单: {ticket}
"""
