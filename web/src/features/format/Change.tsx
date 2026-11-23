import {
  signedChange,
  type ChangeKind,
  type SignedText,
} from './signedChange.ts';
import styles from './Change.module.css';

export function SignedValue({ change }: { change: SignedText }) {
  return (
    <span
      className={`${styles.change} ${styles[change.direction]}`}
      data-direction={change.direction}
    >
      {change.text}
    </span>
  );
}

// A gain or loss: signed text, a direction colour and an arrow from CSS.
export function Change({ value, kind }: { value: string; kind: ChangeKind }) {
  return <SignedValue change={signedChange(value, kind)} />;
}
