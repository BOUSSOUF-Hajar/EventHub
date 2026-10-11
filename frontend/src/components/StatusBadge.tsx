import type { BookingStatus } from "../types";
import { STATUS_LABEL, isTerminal } from "../lib/saga";

export default function StatusBadge({ status }: { status: BookingStatus }) {
  return (
    <span className={`badge badge--${status.toLowerCase().replace("_", "-")}`}>
      {/* Le point pulse tant que la Saga est en cours : le statut va encore changer. */}
      {!isTerminal(status) && <span className="badge__pulse" aria-hidden="true" />}
      {STATUS_LABEL[status]}
    </span>
  );
}
