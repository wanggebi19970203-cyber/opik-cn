import React, { ReactNode, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";

type SingleLineExpandableTextProps = {
  children: ReactNode;
  showMoreLabel?: string;
  showLessLabel?: string;
  className?: string;
  buttonClassName?: string;
};

const SingleLineExpandableText: React.FC<SingleLineExpandableTextProps> = ({
  children,
  showMoreLabel,
  showLessLabel,
  className,
  buttonClassName,
}) => {
  const { t } = useTranslation();
  const defaultShowMoreLabel = showMoreLabel || t("common.shared.showMore");
  const defaultShowLessLabel = showLessLabel || t("common.shared.showLess");
  const [isExpanded, setIsExpanded] = useState(false);
  const [isOverflowing, setIsOverflowing] = useState(false);
  const measureRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = measureRef.current;
    if (!el) return;

    const measure = () => {
      setIsOverflowing(el.scrollWidth > el.clientWidth);
    };
    measure();

    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  }, [children]);

  const toggle = () => setIsExpanded((prev) => !prev);

  return (
    <div className={cn("relative min-w-0 flex-1", className)}>
      <div
        ref={measureRef}
        aria-hidden="true"
        className="pointer-events-none invisible absolute inset-x-0 top-0 truncate"
      >
        {children}
      </div>

      {isExpanded ? (
        <div className="min-w-0 break-words">
          {children}
          {isOverflowing && (
            <button
              type="button"
              className={cn("ml-1 underline", buttonClassName)}
              onClick={toggle}
            >
              {defaultShowLessLabel}
            </button>
          )}
        </div>
      ) : (
        <div className="flex min-w-0 items-baseline gap-1">
          <div className="min-w-0 flex-1 truncate">{children}</div>
          {isOverflowing && (
            <button
              type="button"
              className={cn("shrink-0 underline", buttonClassName)}
              onClick={toggle}
            >
              {defaultShowMoreLabel}
            </button>
          )}
        </div>
      )}
    </div>
  );
};

export default SingleLineExpandableText;
