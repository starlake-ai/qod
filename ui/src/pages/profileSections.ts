/** Which Profile sections a session may load. A staff session reaches only whoami, logout and its
 *  own tokens, so every data-plane card (usage, statements, S3 credentials, recoverable tables) is off. */
export interface ProfileSections { usage: boolean; s3: boolean; recoverable: boolean }

export function profileSectionsFor(staff: boolean): ProfileSections {
  return { usage: !staff, s3: !staff, recoverable: !staff };
}
