// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage.seed;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.document.FolderAccessControlEntry;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIStorageBackend;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.MatterService;
import com.logicaldoc.healdan.matter.NewMatter;
import com.logicaldoc.healdan.seed.SeedStep;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.storage.StorageBackend;
import com.logicaldoc.healdan.storage.StorageBackendDAO;
import com.logicaldoc.healdan.storage.StorageSettings;
import com.logicaldoc.healdan.storage.crypto.BackendKeys;
import com.logicaldoc.healdan.storage.crypto.RecoveryKey;
import com.logicaldoc.healdan.storage.web.StorageAdmin;
import com.logicaldoc.util.spring.Context;

/**
 * For the smoke test only: when {@code HEALDAN_SMOKE_STORAGE} is {@code true}, as
 * {@code docker/tests/compose.storage.yaml} sets it, and {@code HEALDAN_SMOKE_FIXTURE} is {@code true}, as
 * {@code docker/tests/compose.smoke.yaml} sets it, sets up storage on the stand-in S3 server, Garage, that the storage
 * overlay runs (S3 spec section 16.1):
 * <ul>
 * <li>the encrypted S3 backend {@value #BACKEND}, added as an administrator adds one on the Storage tab, through
 * {@link StorageAdmin#add}: Test, the row, its marker object, its key file, its check object and the full Test; then
 * its recovery key confirmed, as the tab asks;</li>
 * <li>the Store {@value #STORE}, which Everyone reads as they read the smoke fixture's {@code Smoke}, keeping its
 * files on that backend;</li>
 * <li>matters 1.2 and 1.3 (call 19) in it, under the smoke fixture's client 1, from its {@code Smoke template};</li>
 * <li>{@code storage.sweepSeconds} and {@code storage.retestSeconds} at 5 (call 12), so the smoke test's outage
 * check waits seconds, not minutes.</li>
 * </ul>
 * The S3 settings come from {@code HEALDAN_SMOKE_S3_ENDPOINT}, {@code _REGION}, {@code _BUCKET},
 * {@code _ACCESS_KEY} and {@code _SECRET}, which the overlay sets with fixed test values; with the switch on and one
 * missing, the step fails, and startup with it.
 * <p>
 * The backend is added outside a transaction, as its add calls Garage; a start that fails after it finds it by its
 * name and goes on with it. The Store and the matters are made in one transaction, and the Store's backend and the
 * settings are set only if they aren't already, so a later start resumes where a failed one stopped.
 * <p>
 * Production never sets the variables. Without both switches the step does nothing and writes no marker, so it can run
 * on a later start. Nor does it on a server whose store isn't Healdan's, as the other storage seeds.
 */
@Component
public class SmokeStorageSeed implements SeedStep {

	static final String SWITCH = "HEALDAN_SMOKE_STORAGE";

	/** {@code SmokeFixtureSeed}'s switch, which only the smoke overlay sets */
	static final String FIXTURE = "HEALDAN_SMOKE_FIXTURE";

	static final String ENDPOINT = "HEALDAN_SMOKE_S3_ENDPOINT";

	static final String REGION = "HEALDAN_SMOKE_S3_REGION";

	static final String BUCKET = "HEALDAN_SMOKE_S3_BUCKET";

	static final String ACCESS_KEY = "HEALDAN_SMOKE_S3_ACCESS_KEY";

	static final String SECRET = "HEALDAN_SMOKE_S3_SECRET";

	private static final List<String> S3_VARIABLES = List.of(ENDPOINT, REGION, BUCKET, ACCESS_KEY, SECRET);

	static final String BACKEND = "Smoke Garage";

	/** Where in the bucket the backend keeps its objects */
	static final String PREFIX = "healdan/";

	static final String STORE = "Smoke Cloud";

	/** The smoke fixture's template and client */
	static final String TEMPLATE = "Smoke template";

	static final long CLIENT = 1;

	/** Matter 1.2 moves home to Smoke in the smoke test's check 5; 1.3 stays, for step 8 to read back (call 19) */
	static final Map<Long, String> MATTERS = Map.of(2L, "Cloud Matter", 3L, "Second Cloud Matter");

	static final int SECONDS = 5;

	private static final Logger log = LoggerFactory.getLogger(SmokeStorageSeed.class);

	private final Supplier<Map<String, String>> environment;

	/** Where the seed learns which store CE runs; tests set it */
	Supplier<Store> store = Store::get;

	@Autowired
	public SmokeStorageSeed() {
		this(System::getenv);
	}

	SmokeStorageSeed(Supplier<Map<String, String>> environment) {
		this.environment = environment;
	}

	@Override
	public String name() {
		return "smoke-storage";
	}

	@Override
	public boolean enabled() {
		Map<String, String> env = environment.get();
		if (!"true".equals(env.get(SWITCH)))
			return false;
		if (!"true".equals(env.get(FIXTURE))) {
			log.warn("The smoke test's storage seed needs {}=true too, which the smoke overlay sets: it does nothing on "
					+ "a stack that isn't the smoke test's", FIXTURE);
			return false;
		}
		return StorageSettingsSeed.enabledFor(store.get());
	}

	@Override
	public void run() throws Exception {
		Map<String, String> env = environment.get();
		List<String> missing = S3_VARIABLES.stream().filter(name -> StringUtils.isBlank(env.get(name))).toList();
		if (!missing.isEmpty())
			throw new IllegalStateException(("%s is true, and the smoke test's storage seed needs %s too, which "
					+ "docker/tests/compose.storage.yaml sets").formatted(SWITCH, String.join(", ", missing)));

		User admin = UserDAO.get().findByUsername("admin");
		if (admin == null)
			throw new IllegalStateException("The smoke test's storage seed adds its backend as admin, who isn't there");
		StorageAdmin storage = StorageAdmin.get();

		long backendId = backend(storage, env, admin);
		long storeId = new TransactionTemplate(Context.get(PlatformTransactionManager.class)).execute(status -> {
			try {
				return storeAndMatters(admin);
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new IllegalStateException(e.getMessage(), e);
			}
		});
		storage.setStoreBackend(storeId, backendId, admin);

		StorageSettings settings = StorageSettings.get();
		settings.setSweepSeconds(SECONDS);
		settings.setRetestSeconds(SECONDS);
	}

	/**
	 * @return the backend's id: added now, with its recovery key confirmed, or one an earlier start added, confirmed
	 *         if that start stopped before
	 */
	private static long backend(StorageAdmin storage, Map<String, String> env, User admin) throws Exception {
		Optional<StorageBackend> earlier = StorageBackendDAO.get().findAll().stream()
				.filter(row -> row.getDeleted() == 0 && BACKEND.equals(row.getName())).findFirst();
		long id;
		String recoveryKey;
		if (earlier.isPresent()) {
			id = earlier.get().getId();
			if (earlier.get().isKeyConfirmedOn())
				return id;
			byte[] key = Context.get(BackendKeys.class).read(id).orElseThrow(() -> new IllegalStateException(
					"Storage backend " + BACKEND + " has no key file: remove the smoke stack's volumes and start again"));
			recoveryKey = RecoveryKey.format(key);
		} else {
			GUIStorageBackend draft = new GUIStorageBackend();
			draft.setKind(GUIStorageBackend.S3);
			draft.setName(BACKEND);
			draft.setEndpoint(env.get(ENDPOINT).strip());
			draft.setRegion(env.get(REGION).strip());
			draft.setBucket(env.get(BUCKET).strip());
			draft.setPrefix(PREFIX);
			// Garage serves buckets by path; its virtual hosts would need DNS names the stack doesn't have
			draft.setPathStyle(true);
			draft.setAccessKey(env.get(ACCESS_KEY).strip());
			draft.setEncrypted(true);
			GUIStorageBackend added = storage.add(draft, env.get(SECRET).strip(), admin);
			id = added.getId();
			recoveryKey = added.getRecoveryKey();
		}
		// As the tab asks: the last group of four, typed back
		storage.confirmRecoveryKey(id, recoveryKey.substring(recoveryKey.length() - 4), admin);
		return id;
	}

	/** @return the Store's id: made now with its matters, or found, with them, as an earlier start made it */
	private static long storeAndMatters(User admin) throws Exception {
		FolderDAO dao = FolderDAO.get();
		Folder root = dao.findRoot(Tenant.DEFAULT_ID);
		List<Folder> found = dao.findByNameAndParentId(STORE, root.getId());
		if (!found.isEmpty())
			return found.get(0).getId();

		Folder templates = dao.findById(HealdanSettings.get().templatesStoreId());
		List<Folder> template = dao.findByNameAndParentId(TEMPLATE, templates.getId());
		Client client = ClientDAO.get().findByNumber(CLIENT);
		if (template.isEmpty() || client == null)
			throw new IllegalStateException("The smoke test's storage seed puts its matters under the smoke fixture's "
					+ "client " + CLIENT + ", from its " + TEMPLATE + ", and the fixture isn't there");

		Folder vo = new Folder(STORE);
		vo.setType(Folder.TYPE_WORKSPACE);
		vo.setTenantId(Tenant.DEFAULT_ID);
		Folder store = dao.create(root, vo, false, history(FolderEvent.CREATED));
		// P2, as for Smoke: whoever reads the matter reads its Store
		Group everyone = GroupDAO.get().findByName(HealdanConstants.EVERYONE_GROUP, Tenant.DEFAULT_ID);
		Folder current = dao.findById(store.getId(), true);
		current.setSecurityRef(null);
		current.getAccessControlList().clear();
		FolderAccessControlEntry ace = new FolderAccessControlEntry(everyone.getId());
		ace.grantPermissions(Set.of(Permission.READ));
		current.addAccessControlEntry(ace);
		dao.store(current, history(FolderEvent.PERMISSION));

		for (long number : MATTERS.keySet().stream().sorted().toList())
			MatterService.get().create(new NewMatter(client.getId(), number, MATTERS.get(number), null, store.getId(),
					admin.getId(), template.get(0).getId()), admin);
		return store.getId();
	}

	/*
	 * As SmokeFixtureSeed's: the system user only labels the history, and its id stays 0, as with a real user id CE
	 * adds that user's group to the new folder's ACL
	 */
	private static FolderHistory history(FolderEvent event) {
		FolderHistory history = new FolderHistory();
		history.setUserId(0L);
		history.setUserLogin("_system");
		history.setUsername("System");
		history.setTenantId(Tenant.DEFAULT_ID);
		history.setEvent(event);
		history.setComment("Healdan smoke storage (user id %d)".formatted(User.USERID_SYSTEM));
		return history;
	}
}
