type CommandStatus = { kind: string; deliveryStatus: string; applicationOutcome: unknown };
type PauseUsage = {
  turnsUsed: number;
  turnsGranted: number;
  toolCallsUsed?: number;
  toolCallsGranted?: number;
  activeMillisUsed?: number;
  activeMillisGranted?: number;
};

type Cost = { spentMicros: number; estimatedMicros: number; effectiveLimitMicros: number | null; limitMicros: number | null; bufferPercent: number };

// Exact micro-dollars: at least two decimals, trailing zeros dropped, never rounded.
export function formatDollars(micros: number): string {
  const [whole, fraction] = (micros / 1_000_000).toFixed(6).split('.');
  return `$${whole}.${fraction.replace(/0+$/, '').padEnd(2, '0')}`;
}

const money = formatDollars;

export function dollarsToMicros(value: string): number | null {
  if (!/^(?:0|[1-9]\d*)(?:\.\d{1,6})?$/.test(value)) return null;
  const micros = Math.round(Number(value) * 1_000_000);
  return Number.isSafeInteger(micros) ? micros : null;
}

export function remoteReady(remote: boolean, allowRemote: boolean, limit: string, buffer: string): boolean {
  if (!remote) return true;
  const micros = dollarsToMicros(limit);
  return allowRemote && micros !== null && micros > 0 && /^\d+$/.test(buffer) && Number(buffer) <= 50;
}

export function formatCost(cost: Cost | null, role: string | null, model: string | null): string | null {
  if (!cost || cost.limitMicros === null || cost.effectiveLimitMicros === null) return null;
  return `role ${role ?? 'unknown'} · ${model ?? 'unknown'} · ${money(cost.spentMicros)} (+${money(cost.estimatedMicros)} est) of ${money(cost.effectiveLimitMicros)} / ${money(cost.limitMicros)}`;
}

export function formatCostPause(cost: Cost): string {
  if (cost.effectiveLimitMicros === null || cost.limitMicros === null) return '';
  return `${money(cost.spentMicros)} spent (incl. ${money(cost.estimatedMicros)} estimated) of ${money(cost.effectiveLimitMicros)} effective / ${money(cost.limitMicros)} limit`;
}

const label = (value: string) => value.replaceAll('_', ' ').toLowerCase();

export function formatCommandStatus(command: CommandStatus): string {
  const outcome = command.applicationOutcome;
  const status = typeof outcome === 'string' ? outcome :
    outcome && typeof outcome === 'object' ?
      ('outcome' in outcome && typeof outcome.outcome === 'string' ? outcome.outcome :
        'deliveryStatus' in outcome && typeof outcome.deliveryStatus === 'string' ? outcome.deliveryStatus : null) : null;
  return [label(command.kind), label(command.deliveryStatus), ...(status ? [label(status)] : [])].join(' · ');
}

export function formatPauseUsage(pause: PauseUsage): string {
  const parts = [`Turns ${pause.turnsUsed}/${pause.turnsGranted}`];
  if (pause.toolCallsUsed !== undefined && pause.toolCallsGranted !== undefined)
    parts.push(`tool calls ${pause.toolCallsUsed}/${pause.toolCallsGranted}`);
  if (pause.activeMillisUsed !== undefined && pause.activeMillisGranted !== undefined)
    parts.push(`active ${Math.round(pause.activeMillisUsed / 60000)}/${Math.round(pause.activeMillisGranted / 60000)} min`);
  return parts.join(' · ');
}
