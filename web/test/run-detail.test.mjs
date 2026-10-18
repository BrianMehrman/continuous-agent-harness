import test from 'node:test';
import assert from 'node:assert/strict';
import { formatCommandStatus, formatPauseUsage } from '../src/run-detail.ts';

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
