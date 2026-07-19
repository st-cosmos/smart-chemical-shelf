import type { InputHTMLAttributes } from 'react';

interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: string;
}

export default function Input({ label, className = '', ...rest }: InputProps) {
  return (
    <div className="input-group">
      {label && <span className="input-label">{label}</span>}
      <input className={`input-field ${className}`.trim()} {...rest} />
    </div>
  );
}
