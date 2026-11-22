import { describe, expect, it } from 'vitest';
import { profileSectionsFor } from './profileSections';

describe('profileSectionsFor', () => {
  it('shows every data-plane card to a non-staff session', () => {
    expect(profileSectionsFor(false)).toEqual({ usage: true, s3: true, recoverable: true });
  });
  it('hides them all from staff', () => {
    expect(profileSectionsFor(true)).toEqual({ usage: false, s3: false, recoverable: false });
  });
});
