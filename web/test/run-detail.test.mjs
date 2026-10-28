import test from 'node:test';
import assert from 'node:assert/strict';
import { formatCommandStatus, formatPauseUsage, formatCost, formatCostPause, dollarsToMicros, remoteReady } from '../src/run-detail.ts';

test('renders a command with a JSON application outcome', () => {
  assert.equal(formatCommandStatus({
    kind: 'START', deliveryStatus: 'DELIVERED', applicationOutcome: { outcome: 'STARTED' },
  }), 'start · delivered · started');
  assert.equal(formatCommandStatus({
    kind: 'START', deliveryStatus: 'DELIVERED', applicationOutcome: {},
  }), 'start · delivered');
});

test('renders a legacy pause without inventing missing limits', () => {
  assert.equal(formatPauseUsage({ turnsUsed: 40, turnsGranted: 40 }), 'Turns 40/40');
});

test('formats run cost and cost pause in dollars', () => {
  const cost = { spentMicros: 1120000, estimatedMicros: 40000, effectiveLimitMicros: 4500000, limitMicros: 5000000, bufferPercent: 10 };
  assert.equal(formatCost(cost, 'developer', 'claude-haiku-5-5'), 'role developer · claude-haiku-5-5 · $1.12 (+$0.04 est) of $4.50 / $5.00');
  assert.equal(formatCost(null, 'developer', 'claude-haiku-5-5'), null);
  assert.equal(formatCostPause(cost), '$1.12 spent (incl. $0.04 estimated) of $4.50 effective / $5.00 limit');
});

test('remote submission requires opt-in and valid budget', () => {
  assert.equal(remoteReady(true, false, '0.10', '10'), false);
  assert.equal(remoteReady(true, true, '', '10'), false);
  assert.equal(remoteReady(true, true, '0.10', '51'), false);
  assert.equal(remoteReady(true, true, '0.10', '10'), true);
  assert.equal(remoteReady(false, false, '', ''), true);
  assert.equal(dollarsToMicros('0.10'), 100000);
  assert.equal(dollarsToMicros('0.0000001'), null);
});
