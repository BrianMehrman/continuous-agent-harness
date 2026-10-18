type CommandStatus = { kind: string; deliveryStatus: string; applicationOutcome: unknown };
type PauseUsage = {
  turnsUsed: number;
  turnsGranted: number;
  toolCallsUsed?: number;
  toolCallsGranted?: number;
  activeMillisUsed?: number;
  activeMillisGranted?: number;
};

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
