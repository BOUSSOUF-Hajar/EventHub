import type { ReactNode } from "react";

export function Spinner({ label }: { label: string }) {
  return (
    <p className="spinner" role="status">
      <span className="spinner__dot" aria-hidden="true" />
      {label}
    </p>
  );
}

interface NoticeProps {
  tone: "info" | "success" | "warning" | "error";
  title?: string;
  children: ReactNode;
}

export function Notice({ tone, title, children }: NoticeProps) {
  return (
    <div className={`notice notice--${tone}`} role={tone === "error" ? "alert" : "status"}>
      {title && <p className="notice__title">{title}</p>}
      <div className="notice__body">{children}</div>
    </div>
  );
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <p className="empty__title">{title}</p>
      {children}
    </div>
  );
}
