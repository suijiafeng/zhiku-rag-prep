import type { OverviewData } from "./fixtures";

/** 纯 CSS 柱状图；按设计稿高亮最近一天。 */
export function DailyQuestionsChart({ data }: { data: OverviewData["dailyQuestions"] }) {
  const max = Math.max(1, ...data.map((d) => d.count));
  return (
    <div className="bar-chart">
      {data.map((d, i) => (
        <div key={d.day} className="bar-column" role="img" aria-label={`${d.day} 问答 ${d.count} 次`}>
          <span className="bar-value">{d.count}</span>
          <div
            className={i === data.length - 1 ? "bar bar-latest" : "bar"}
            style={{ height: `${Math.round((d.count / max) * 100)}%` }}
          />
          <span className="bar-label">{d.day}</span>
        </div>
      ))}
    </div>
  );
}
