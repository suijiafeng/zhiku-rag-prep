import { Fragment } from "react";

/** Renders untrusted answer text as plain text; only [n] markers with a matching citation become buttons. */
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
