import type { BookingStatus } from "../types";

/**
 * Traduit le statut d'une reservation en etapes de la Saga, pour l'afficher.
 *
 * L'API n'expose que le statut courant, pas l'historique : les etapes passees sont
 * deduites de la machine a etats du booking-service (PENDING -> AWAITING_PAYMENT ->
 * CONFIRMED | CANCELLED), dont l'ordre est garanti.
 */
export type StepState = "done" | "current" | "upcoming" | "failed" | "compensated";

export interface SagaStep {
  key: "lock" | "request" | "payment" | "outcome";
  title: string;
  description: string;
  /** Brique technique qui porte l'etape, affichee en etiquette. */
  component: string;
  state: StepState;
}

export function isTerminal(status: BookingStatus): boolean {
  return status === "CONFIRMED" || status === "CANCELLED";
}

export const STATUS_LABEL: Record<BookingStatus, string> = {
  PENDING: "En attente",
  AWAITING_PAYMENT: "Paiement en cours",
  CONFIRMED: "Confirmée",
  CANCELLED: "Annulée",
};

export function sagaSteps(status: BookingStatus): SagaStep[] {
  const cancelled = status === "CANCELLED";
  const confirmed = status === "CONFIRMED";
  const requestSent = status !== "PENDING";

  return [
    {
      key: "lock",
      title: "Places verrouillées",
      description: "Vos places sont retirées du stock disponible et la réservation est enregistrée.",
      component: "Verrou Redis",
      state: "done",
    },
    {
      key: "request",
      title: "Demande de paiement transmise",
      description: requestSent
        ? "La demande a été publiée de façon fiable vers le service de paiement."
        : "La demande est en cours de publication vers le service de paiement.",
      component: "Outbox → RabbitMQ",
      state: requestSent ? "done" : "current",
    },
    {
      key: "payment",
      title: cancelled ? "Paiement non abouti" : "Paiement",
      description: cancelled
        ? "Le paiement a été refusé ou n'a pas été finalisé dans le délai imparti."
        : confirmed
          ? "Le paiement a été accepté."
          : "Le service de paiement traite votre demande.",
      component: "Service de paiement",
      state: cancelled ? "failed" : confirmed ? "done" : status === "AWAITING_PAYMENT" ? "current" : "upcoming",
    },
    {
      key: "outcome",
      title: cancelled ? "Réservation annulée, places restituées" : "Réservation confirmée",
      description: cancelled
        ? "Action compensatoire : vos places ont été remises en vente. Aucun montant n'a été débité."
        : confirmed
          ? "Vos places sont définitivement attribuées. Un e-mail de confirmation vous a été envoyé."
          : "Vos places vous seront attribuées dès que le paiement sera accepté.",
      component: cancelled ? "Saga : compensation" : "Saga : confirmation",
      state: cancelled ? "compensated" : confirmed ? "done" : "upcoming",
    },
  ];
}
