package com.logicaldoc.healdan.seed.demo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The demo firm, as {@code docker/demo/manifest.json} describes it (demo spec
 * §4, §5.2). A list the manifest leaves out reads as empty; a field it
 * doesn't know is refused, so a misspelling fails the seed rather than being
 * ignored.
 * <p>
 * An ACL is a map from a group's name to the names of {@code Permission}s,
 * such as {@code {"Everyone": ["READ"]}}.
 */
public record DemoManifest(List<GroupSpec> groups, List<UserSpec> users, List<ClassSpec> classes,
		List<StoreSpec> stores, List<TemplateSpec> templates, List<ClientSpec> clients, List<MatterSpec> matters,
		List<Step> timeline) {

	public DemoManifest {
		groups = orEmpty(groups);
		users = orEmpty(users);
		classes = orEmpty(classes);
		stores = orEmpty(stores);
		templates = orEmpty(templates);
		clients = orEmpty(clients);
		matters = orEmpty(matters);
		timeline = orEmpty(timeline);
	}

	public record GroupSpec(String name, String description) {
	}

	/** @param groups the groups besides Everyone, CE's included */
	public record UserSpec(String username, String firstName, String name, String email, List<String> groups) {
		public UserSpec {
			groups = orEmpty(groups);
		}
	}

	/** A Class, and the Subclasses that belong to it */
	public record ClassSpec(String name, List<String> subclasses) {
		public ClassSpec {
			subclasses = orEmpty(subclasses);
		}
	}

	public record StoreSpec(String name, Map<String, List<String>> acl) {
		public StoreSpec {
			acl = orEmpty(acl);
		}
	}

	/** @param acl null when the folder inherits its parent's */
	public record FolderSpec(String name, Map<String, List<String>> acl, List<FolderSpec> folders) {
		public FolderSpec {
			folders = orEmpty(folders);
		}
	}

	/** @param kind {@code ALL}, {@code DOCUMENTS} or {@code EMAILS}; null for all */
	public record SearchFolderSpec(String name, int days, boolean onlyMine, String kind,
			@JsonProperty("class") String docClass, String subclass) {
	}

	public record TemplateSpec(String name, Map<String, List<String>> acl, List<FolderSpec> folders,
			List<SearchFolderSpec> searchFolders) {
		public TemplateSpec {
			acl = orEmpty(acl);
			folders = orEmpty(folders);
			searchFolders = orEmpty(searchFolders);
		}
	}

	public record ClientSpec(long number, String name) {
	}

	/**
	 * @param ref the matter's number, such as {@code 998.3}
	 * @param acl null to keep the template's; otherwise the workspace's ACL,
	 *        to which the owner's personal group is added with every
	 *        permission
	 */
	public record MatterSpec(String ref, String name, String description, String store, String owner,
			String template, Map<String, List<String>> acl) {
	}

	/**
	 * One action on the timeline. {@link DemoTimeline} lists what each needs.
	 *
	 * @param day days before the stack's first start, so -1 is the day
	 *        before
	 * @param time {@code HH:mm}, or null for 09:00
	 * @param as the username of the person acting
	 * @param action what they do
	 * @param key a name later steps use for the document this one files
	 * @param doc the key of the document this step acts on
	 * @param target the key of the second document, for a link
	 */
	public record Step(int day, String time, String as, @JsonProperty("do") String action, String key,
			String matter, String folder, String file, String name, String doc, String target, String author,
			String typist, @JsonProperty("class") String docClass, String subclass, String comments,
			List<String> tags, boolean release, String text, String subject, List<String> to, String password) {
		public Step {
			tags = orEmpty(tags);
			to = orEmpty(to);
		}
	}

	public static DemoManifest read(Path file) throws IOException {
		return new ObjectMapper().readValue(file.toFile(), DemoManifest.class);
	}

	private static <T> List<T> orEmpty(List<T> list) {
		return list == null ? List.of() : list;
	}

	private static <K, V> Map<K, V> orEmpty(Map<K, V> map) {
		return map == null ? Map.of() : map;
	}
}
