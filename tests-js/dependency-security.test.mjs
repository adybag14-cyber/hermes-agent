import { describe, expect, it } from 'vitest';
import browserslist from 'browserslist';
import sanitizeHtml from 'sanitize-html';

describe('inherited frontend dependency security', () => {
  it('does not confuse custom stats keys with inherited object properties', () => {
    const stats = JSON.parse('{"toString":{"onekey":5},"chrome":{"100":50}}');
    const result = browserslist('defaults', { stats });
    expect(Array.isArray(result)).toBe(true);
    expect(result.length).toBeGreaterThan(0);
  });

  it('rejects unsafe SVG animation destinations while preserving safe animation', () => {
    const options = {
      allowedTags: sanitizeHtml.defaults.allowedTags.concat(['svg', 'animate', 'text']),
      allowedAttributes: {
        ...sanitizeHtml.defaults.allowedAttributes,
        animate: ['attributename', 'values', 'dur', 'fill'],
        text: ['y'],
      },
      allowedSchemesAppliedToAttributes: sanitizeHtml.defaults.allowedSchemesAppliedToAttributes.concat(['values']),
    };
    const unsafe = '<svg><a><animate attributeName="href" values="#safe;javascript:void(0)" dur=".01s" fill="freeze"></animate><text y="30">Link</text></a></svg>';
    const safe = '<svg><animate attributeName="fill" values="red;blue" dur="1s"></animate></svg>';
    expect(sanitizeHtml(unsafe, options)).not.toContain('javascript:');
    expect(sanitizeHtml(safe, options)).toContain('values="red;blue"');
  });
});
