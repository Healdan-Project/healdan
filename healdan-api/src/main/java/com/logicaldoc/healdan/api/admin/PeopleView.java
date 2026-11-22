// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static com.logicaldoc.healdan.api.admin.ListOrder.date;

import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.logicaldoc.healdan.admin.people.CheckedOut;
import com.logicaldoc.healdan.admin.people.DeviceRow;
import com.logicaldoc.healdan.admin.people.GroupDetail;
import com.logicaldoc.healdan.admin.people.GroupRow;
import com.logicaldoc.healdan.admin.people.MemberRow;
import com.logicaldoc.healdan.admin.people.NotEmailed;
import com.logicaldoc.healdan.admin.people.Person;
import com.logicaldoc.healdan.admin.people.PersonRow;
import com.logicaldoc.healdan.admin.people.SignsInWith;
import com.logicaldoc.healdan.admin.people.UserOptions;

/**
 * The people routes' answers, as {@code openapi-admin.yaml}'s People schemas give them, made from the people
 * services' read models. A temporary password is only in {@link CreatedUser} and {@link TemporaryPassword}, and in
 * neither's {@code toString()}.
 */
public final class PeopleView {

	private PeopleView() {
	}

	/**
	 * {@code GroupBrief}: a group named in another object.
	 */
	public record GroupBrief(long id, String name) {

		static GroupBrief of(PersonRow.GroupRef group) {
			return new GroupBrief(group.id(), group.name());
		}
	}

	/**
	 * {@code ProviderBrief}: a sign-in provider named in another object, its label its id once it's been removed.
	 */
	public record ProviderBrief(String id, String label) {

		static ProviderBrief of(SignsInWith.Provider provider) {
			return provider != null ? new ProviderBrief(provider.id(), provider.label()) : null;
		}
	}

	/**
	 * {@code SignsInWith}: {@code password}, or {@code provider} with the provider the account is linked to.
	 */
	public record SignsInWithView(String kind, ProviderBrief provider) {

		static SignsInWithView of(SignsInWith signsInWith) {
			return new SignsInWithView(signsInWith.kind().code(), ProviderBrief.of(signsInWith.provider()));
		}
	}

	/**
	 * {@code UserRow}: a person, as the Users list shows them.
	 */
	public record UserRow(long id, String username, String name, String firstName, String lastName, String email,
			List<GroupBrief> groups, SignsInWithView signsInWith, String status, Date blockedUntil, Date lastSignIn,
			long devices, boolean readOnly, boolean admin) {

		static UserRow of(PersonRow row) {
			return new UserRow(row.id(), row.username(), row.name(), row.firstName(), row.lastName(), row.email(),
					row.groups().stream().map(GroupBrief::of).toList(), SignsInWithView.of(row.signsInWith()),
					row.status().code(), date(row.blockedUntil()), date(row.lastSignIn()), row.devices(),
					row.readOnly(), row.admin());
		}
	}

	/**
	 * {@code UserRefusals}: the sentence each of a person's page's actions would be refused with now; null for none.
	 */
	public record UserRefusals(String disable, String enable, String resetPassword, String leaveAdmin) {

		static UserRefusals of(Person.Refusals refusals) {
			return new UserRefusals(refusals.disable(), refusals.enable(), refusals.resetPassword(),
					refusals.leaveAdmin());
		}
	}

	/**
	 * {@code User}: a person, for their page: the row and what the page shows besides.
	 */
	public record UserView(long id, String username, String name, String firstName, String lastName, String email,
			List<GroupBrief> groups, SignsInWithView signsInWith, String status, Date blockedUntil, Date lastSignIn,
			long devices, boolean readOnly, boolean admin, String language, String timeZone, Date created,
			Date passwordChanged, boolean mustChangePassword, long checkedOut, long mattersOwned,
			UserRefusals refusals) {

		static UserView of(Person person) {
			UserRow row = UserRow.of(person.row());
			return new UserView(row.id(), row.username(), row.name(), row.firstName(), row.lastName(), row.email(),
					row.groups(), row.signsInWith(), row.status(), row.blockedUntil(), row.lastSignIn(),
					row.devices(), row.readOnly(), row.admin(), person.language(), person.timeZone(),
					date(person.created()), date(person.passwordChanged()), person.mustChangePassword(),
					person.checkedOut(), person.mattersOwned(), UserRefusals.of(person.refusals()));
		}
	}

	/**
	 * {@code LanguageOption}: a language a person can be given, named in English.
	 */
	public record LanguageOption(String code, String name) {
	}

	/**
	 * {@code UserOptions}: what New User and Edit offer.
	 */
	public record UserOptionsView(List<LanguageOption> languages, String defaultLanguage, List<String> timeZones,
			List<ProviderBrief> providers, boolean outgoingEmail) {

		static UserOptionsView of(UserOptions options) {
			return new UserOptionsView(
					options.languages().stream().map(l -> new LanguageOption(l.code(), l.name())).toList(),
					options.defaultLanguage(), options.timeZones(),
					options.providers().stream().map(ProviderBrief::of).toList(), options.outgoingEmail());
		}
	}

	/**
	 * @return why a temporary password wasn't emailed, as the contract's {@code notEmailed} says it, such as
	 *         {@code not-set-up}; null when it was emailed
	 */
	static String notEmailed(NotEmailed why) {
		return why != null ? why.name().toLowerCase(Locale.ROOT).replace('_', '-') : null;
	}

	/**
	 * {@code CreatedUser}: a person just added, and their temporary password, with why, unless it was emailed.
	 */
	public record CreatedUser(UserView user, String temporaryPassword, boolean emailed, String notEmailed) {

		@Override
		public String toString() {
			// Never the password
			return "CreatedUser[user=%d, emailed=%b]".formatted(user.id(), emailed);
		}
	}

	/**
	 * {@code TemporaryPassword}: a reset's temporary password, with why, unless it was emailed.
	 */
	public record TemporaryPassword(String temporaryPassword, boolean emailed, String notEmailed) {

		@Override
		public String toString() {
			// Never the password
			return "TemporaryPassword[emailed=%b]".formatted(emailed);
		}
	}

	/**
	 * {@code SignedOut}: how many sessions Sign Out Everywhere closed and device tokens it revoked.
	 */
	public record SignedOutView(long sessions, long devices) {
	}

	/**
	 * {@code UserDevice}: a device a person is signed in on, by its token, never the token itself.
	 */
	public record UserDevice(long id, String name, String device, ProviderBrief provider, Date created,
			Date lastUsed, Date expires) {

		static UserDevice of(DeviceRow device) {
			return new UserDevice(device.id(), device.name(), device.device(), ProviderBrief.of(device.provider()),
					date(device.created()), date(device.lastUsed()), date(device.expires()));
		}
	}

	/**
	 * {@code MatterBrief}: a matter named in another object, by the matter's id.
	 */
	public record MatterBrief(long id, String number, String name) {
	}

	/**
	 * {@code UserCheckout}: a document a person has checked out.
	 */
	public record UserCheckout(long docNumber, String fileVersion, String title, MatterBrief matter, Date since,
			String device, String deviceId, String app) {

		static UserCheckout of(CheckedOut held) {
			CheckedOut.Matter matter = held.matter();
			return new UserCheckout(held.docNumber(), held.fileVersion(), held.title(),
					matter != null ? new MatterBrief(matter.id(), matter.number(), matter.name()) : null,
					date(held.since()), held.device(), held.deviceId(), held.app());
		}
	}

	/**
	 * {@code Group}: a group, not a person's own, for the Groups list and the forms that name one.
	 */
	public record GroupView(long id, String name, String description, long members, boolean fixed, boolean admin,
			List<String> mappedBy) {

		static GroupView of(GroupRow row) {
			return new GroupView(row.id(), row.name(), row.description(), row.members(), row.fixed(), row.admin(),
					row.mappedBy());
		}
	}

	/**
	 * {@code GroupDetail}: a group, for its page, with how many folders grant it something.
	 */
	public record GroupDetailView(long id, String name, String description, long members, boolean fixed,
			boolean admin, List<String> mappedBy, long folders) {

		static GroupDetailView of(GroupDetail detail) {
			GroupRow row = detail.row();
			return new GroupDetailView(row.id(), row.name(), row.description(), row.members(), row.fixed(),
					row.admin(), row.mappedBy(), detail.folders());
		}
	}

	/**
	 * {@code GroupMember}: a person in a group.
	 */
	public record GroupMember(long id, String username, String name, String status, boolean readOnly) {

		static GroupMember of(MemberRow member) {
			return new GroupMember(member.id(), member.username(), member.name(), member.status().code(),
					member.readOnly());
		}
	}
}
