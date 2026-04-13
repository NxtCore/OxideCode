---
name: release-notes
description: Updates the changelog in both UpdateChangesNotification.kt and plugin.xml based on the current branch's changes
---

# Release Notes

This Skill helps you update the changelog in both `UpdateChangesNotification.kt` and `plugin.xml` based on the current branch's changes.

## Instructions

1. **Get the current branch name** using `git branch --show-current`

2. **Get the commit history for the current branch** using `git log --oneline origin/dev..HEAD` (or `git log --oneline dev..HEAD` if dev is local)
   - If no commits are returned, check if you're on the dev branch or if changes have already been merged
   - Use `git log --oneline -10` to see recent commits if the above doesn't work

3. **Analyze the commits** to understand what changes were made. Look for:
   - New features
   - Bug fixes
   - Improvements
   - Breaking changes
   - Skip merge commits unless they contain important information

4. **Format the changelog entries** following the existing pattern:
   - Use `<li>` tags for each bullet point
   - Start with a verb (e.g., "Fixed", "Added", "Improved", "Changed")
   - Be concise but descriptive
   - Use sentence case (not title case)
   - End with a period
   - Group related changes under appropriate categories

5. **Update the files**:
   - **UpdateChangesNotification.kt**: Update all three content strings (`cloudContent`, `gatewayContent`, `nonCloudContent`) with the new changelog entries
   - **plugin.xml**: Update the `<change-notes>` section with the same changelog entries

6. **Follow the existing structure**:
   - Keep the "Background Bash Tool" section if it's still relevant
   - Add new entries to the "Fixes and Improvements" section
   - Maintain the HTML structure with proper indentation
   - Keep the documentation and Discord links at the end

7. **Verify the changes**:
   - Ensure all three content strings in UpdateChangesNotification.kt are identical
   - Ensure the plugin.xml change-notes match the UpdateChangesNotification content
   - Check that the HTML is properly formatted

## Example Format

```html
<li><b>New Feature Name</b>
  <ul>
      <li>Description of the new feature.</li>
  </ul>
</li>
<li><b>Fixes and Improvements</b>
  <ul>
      <li>Fixed bug where something was broken.</li>
      <li>Improved something to work better.</li>
      <li>Added new functionality for X.</li>
  </ul>
</li>
```

## Important Notes

- The changelog should reflect what users will see in the "What's new" notification
- Keep entries user-facing and technical details minimal
- If a change is internal-only, consider whether it should be mentioned
- The version number is automatically pulled from `getCurrentSweepPluginVersion()`
- Make sure to update ALL three content strings in UpdateChangesNotification.kt (cloudContent, gatewayContent, nonCloudContent)

## Troubleshooting

- **No commits found**: If `git log origin/dev..HEAD` returns nothing, you might be on the dev branch. Use `git log --oneline -10` to see recent commits instead.
- **Already updated**: Check if the latest commit is a release notes update - if so, the notes may already be current.
- **Merge conflicts**: If there are conflicts when updating, ensure you're using the latest version of both files.
- **HTML formatting**: If the HTML looks broken, check for unclosed tags or mismatched indentation.
