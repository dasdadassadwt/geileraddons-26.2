package geiler.addons.client.collections;

/** Offline checks for independent, stable-ID nested folder operations. */
public final class FolderTreeChecks {
	private FolderTreeChecks() { }

	public static void run() {
		check(FolderTree.Folder.class.isRecord(), "folder remains a Java record");
		java.lang.reflect.RecordComponent[] components = FolderTree.Folder.class.getRecordComponents();
		check(components.length == 3 && components[0].getName().equals("id")
			&& components[1].getName().equals("name") && components[2].getName().equals("parentId"),
			"record component names and order remain compatible");
		check(new FolderTree.Folder(null, "name", null).toString()
			.equals("Folder[id=null, name=name, parentId=null]"), "record string form remains compatible");
		FolderTree tree = new FolderTree();
		check(tree.create(null, null) == null && tree.create(" \t ", null) == null,
			"null and whitespace-only names are rejected");
		FolderTree.Folder first = tree.create("Repeated name", null);
		FolderTree.Folder second = tree.create("Repeated name", null);
		check(first != null && second != null && !first.id().equals(second.id()),
			"same-name folders have distinct stable ids");
		check(tree.rename(first.id(), "Renamed"), "folder can be renamed");
		check(first.id().equals(tree.folder(first.id()).id()), "rename preserves the folder id");
		FolderTree.Folder child = tree.create("Child", first.id());
		check(child != null && tree.move(child.id(), second.id()), "folder can be reparented");
		check(second.id().equals(tree.folder(child.id()).parentId()), "reparent updates the child parent id");
		check(!tree.move(second.id(), child.id()), "moving a folder below its own descendant is rejected");
		check(tree.move(child.id(), first.id()), "folder can be moved back under another parent");

		String formerParent = tree.delete(first.id());
		check(formerParent == null, "deleting a root folder promotes its contents to root");
		check(tree.folder(child.id()) != null && tree.folder(child.id()).parentId() == null,
			"deleting a populated folder promotes its child folders");
		check(tree.contains(second.id()), "deleting a folder does not delete a sibling with the same name");
		check(tree.create("  " + "n".repeat(52) + "  ", null).name().length() == 48,
			"names are trimmed and capped at 48 characters");
		try {
			tree.folders().clear();
			throw new AssertionError("folder snapshots must be immutable");
		} catch (UnsupportedOperationException expected) {
			// The internal hierarchy is never exposed for direct mutation.
		}

		tree.restore(java.util.List.of(
			new FolderTree.Folder("legacy-root", "Legacy", null),
			new FolderTree.Folder("cycle-a", "A", "cycle-b"),
			new FolderTree.Folder("cycle-b", "B", "cycle-a")));
		check(tree.folder("legacy-root").parentId() == null, "legacy entries without a folder remain at root");
		check(tree.folder("cycle-a").parentId() == null || tree.folder("cycle-b").parentId() == null,
			"malformed saved folder cycles are repaired");

		String nonBreakingSpace = "\u00a0";
		FolderTree whitespaceTree = new FolderTree();
		whitespaceTree.restore(java.util.List.of(
			new FolderTree.Folder(nonBreakingSpace, "Legacy parent", null),
			new FolderTree.Folder("legacy-child", "Legacy child", null)));
		check(whitespaceTree.contains(nonBreakingSpace),
			"folder restore retains ids Java String.isBlank does not classify as blank");
		check(whitespaceTree.move("legacy-child", nonBreakingSpace)
			&& nonBreakingSpace.equals(whitespaceTree.folder("legacy-child").parentId()),
			"move retains non-breaking-space parent ids under Java blank semantics");
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
