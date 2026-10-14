import { Fragment } from "react";

/** 将不可信的回答文本按纯文本渲染；只有存在对应引用的 [n] 标记才渲染为按钮。 */
export function AnswerText({
  text,
  citationCount,
  activeIndex,
  onCite,
}: {
  text: string;
  citationCount: number;
  activeIndex?: number | undefined;
  onCite: (index: number | undefined) => void;
}) {
  const parts = text.split(/(\[\d+\])/u);
  return (
    <p className="answer-text">
      {parts.map((part, i) => {
        const n = /^\[(\d+)\]$/u.exec(part)?.[1];
        const index = n === undefined ? NaN : Number(n);
        if (!(index >= 1 && index <= citationCount)) return <Fragment key={i}>{part}</Fragment>;
        return (
          <button
            key={i}
            type="button"
            className={activeIndex === index ? "cite-mark active" : "cite-mark"}
            aria-label={`来源 ${index}`}
            onMouseEnter={() => onCite(index)}
            onMouseLeave={() => onCite(undefined)}
            onFocus={() => onCite(index)}
            onClick={() => onCite(index)}
          >
            [{index}]
          </button>
        );
      })}
    </p>
  );
}
