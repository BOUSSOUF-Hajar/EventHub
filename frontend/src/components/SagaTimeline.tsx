import type { BookingStatus } from "../types";
import { sagaSteps, type StepState } from "../lib/saga";

const MARKS: Record<StepState, string> = {
  done: "✓",
  current: "",
  upcoming: "",
  failed: "✕",
  compensated: "↺",
};

const STATE_LABELS: Record<StepState, string> = {
  done: "terminée",
  current: "en cours",
  upcoming: "à venir",
  failed: "en échec",
  compensated: "compensée",
};

/**
 * Suivi pas a pas de la Saga de reservation (FE-3). Chaque etape porte le nom de la
 * brique qui la realise : l'ecran montre le parcours du client et, en meme temps, ce
 * qui se passe entre les services.
 */
export default function SagaTimeline({ status }: { status: BookingStatus }) {
  return (
    <ol className="timeline">
      {sagaSteps(status).map((step) => (
        <li key={step.key} className={`timeline__step timeline__step--${step.state}`}>
          <span className="timeline__marker" aria-hidden="true">
            {MARKS[step.state]}
          </span>
          <div className="timeline__content">
            <p className="timeline__title">
              {step.title}
              <span className="visually-hidden"> — étape {STATE_LABELS[step.state]}</span>
            </p>
            <p className="timeline__description">{step.description}</p>
            <span className="timeline__component">{step.component}</span>
          </div>
        </li>
      ))}
    </ol>
  );
}
