// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Globalization;
using System.Text.RegularExpressions;
using Healdan.Helper.Core.Api;

namespace Healdan.Helper.Core.Forms;

/// <summary>
/// What became of the Save window's Save in its email mode (M5 spec §7.3), which files through the window: done, and the
/// window closes; or refused, and it stays open with why, to choose another folder or untick an attachment.
/// </summary>
public abstract partial record SubmitOutcome
{
    private SubmitOutcome()
    {
    }

    /// <summary>Filed, or the matter holds the message already, which counts as filed: the window closes.</summary>
    public static SubmitOutcome Done { get; } = new Filed();

    /// <summary>The window closes: see <see cref="Done"/>.</summary>
    public sealed record Filed : SubmitOutcome;

    /// <summary>
    /// Not filed: the window shows <see cref="Problem"/>, the words for its own problem line, and <see cref="Fields"/>,
    /// the server's field errors, each where it belongs (<see cref="Place"/>), and stays open.
    /// </summary>
    public sealed partial record Refused(string Problem, IReadOnlyList<FieldError> Fields) : SubmitOutcome
    {
        /// <summary>What an outage says: no answer, or a 5xx.</summary>
        public const string CantReach = "Can't reach Healdan. Try again.";

        /// <summary>
        /// Where each part of the refusal shows: a property's error under that field; an attachment's (the server names its
        /// field <c>attachments[3]</c>, by the attachment's index) under that attachment's tick; anything else on the
        /// window's problem line. The line has <see cref="Problem"/> when there were no field errors, the errors that have
        /// no place of their own when there were, and nothing when every one has its place.
        /// </summary>
        /// <param name="isField">Whether the form has a field of that name.</param>
        /// <param name="isAttachment">Whether the window offers the attachment with that index.</param>
        public Placement Place(Func<string, bool> isField, Func<int, bool> isAttachment)
        {
            if (Fields.Count == 0)
                return new Placement([], [], Problem);
            List<(string, string)> onFields = [];
            List<(int, string)> onAttachments = [];
            List<string> rest = [];
            foreach (var error in Fields)
            {
                var field = error.Field ?? ""; // read from JSON, it may lack one
                if (AttachmentIndex(field) is { } index && isAttachment(index))
                    onAttachments.Add((index, error.Detail));
                else if (!field.StartsWith("attachments", StringComparison.Ordinal) && field != "file" && isField(field))
                    onFields.Add((field, error.Detail));
                else
                    rest.Add(error.Detail);
            }
            return new Placement(onFields, onAttachments, rest.Count > 0 ? string.Join(" ", rest) : null);
        }

        /// <summary>The index in a field named <c>attachments[3]</c>, as <c>fileEmail</c> names a ticked attachment's; else null.</summary>
        public static int? AttachmentIndex(string field) =>
            AttachmentField().Match(field) is { Success: true } m
            && int.TryParse(m.Groups[1].Value, NumberStyles.None, CultureInfo.InvariantCulture, out var index) ? index : null;

        [GeneratedRegex(@"^attachments\[(\d+)\]$")]
        private static partial Regex AttachmentField();
    }

    /// <summary>Where a refusal shows: under fields, under attachments, and on the problem line (null for none).</summary>
    public sealed record Placement(
        IReadOnlyList<(string Field, string Detail)> OnFields, IReadOnlyList<(int Index, string Detail)> OnAttachments, string? General);
}
