package geiler.addons.client.module;

import java.util.List;
import java.util.Map;

/** Settings and groups declared as data: every type, group switches, bounds and clear errors. */
public final class ModuleSettingsSpecChecks {
	private static final String SAMPLE = """
		{ "modules": { "Sample": {
		  "settings": [
		    { "id": "on", "type": "toggle", "name": "Enabled Thing", "default": true },
		    { "id": "size", "type": "slider", "name": "Size", "label": "Box Size", "min": 1, "max": 10, "default": 4, "integer": true },
		    { "id": "mode", "type": "dropdown", "name": "Mode", "default": "A", "choices": ["A", "B"], "labels": { "A": "Alpha" } },
		    { "id": "tint", "type": "color", "name": "Tint", "default": [10, 20, 30, 40] },
		    { "id": "solid", "type": "color", "name": "Solid", "default": [1, 2, 3] },
		    { "id": "note", "type": "text", "name": "Note", "default": "hi", "maxLength": 8 },
		    { "id": "key", "type": "text", "name": "Key", "default": "key.keyboard.g", "maxLength": 32, "keybind": true }
		  ],
		  "groups": [
		    { "name": "Main", "settings": ["size", "mode"] },
		    { "name": "Extras", "toggle": "on", "folded": true, "settings": ["tint", "solid"], "groups": [
		        { "name": "Deep", "settings": ["note"] } ] }
		  ] } } }
		""";

	private ModuleSettingsSpecChecks() { }

	public static void run() {
		Map<String, ModuleSettingsSpec.Spec> parsed = ModuleSettingsSpec.parse(SAMPLE);
		ModuleSettingsSpec.Spec spec = parsed.get("Sample");
		check(spec != null, "the module entry is parsed");
		check(spec.getSettings().size() == 7, "all declared settings are created in order");

		BooleanSetting on = spec.toggle("on");
		check("Enabled Thing".equals(on.name()) && on.rawValue(), "toggle keeps its persisted name and default");
		NumberSetting size = spec.slider("size");
		check("Size".equals(size.name()) && "Box Size".equals(size.displayName())
			&& size.min() == 1f && size.max() == 10f && size.value() == 4f && size.isInteger(),
			"slider has persisted name, label, range, default and integer mode");
		ChoiceSetting mode = spec.choice("mode");
		check(mode.choices().equals(List.of("A", "B")) && "A".equals(mode.value()), "dropdown choices and default");
		ColorSetting tint = spec.color("tint");
		check(tint.alphaEditable() && tint.argb() == (40 << 24 | 10 << 16 | 20 << 8 | 30), "RGBA colour default");
		check(!spec.color("solid").alphaEditable(), "an RGB colour is opaque and has no alpha");
		TextSetting note = spec.text("note");
		check("hi".equals(note.value()) && note.maxLength() == 8 && !note.keybindCapture(), "text default and bound");
		check(spec.text("key").keybindCapture(), "a text setting can be a key-capture row");

		List<SettingGroup> groups = spec.getGroups();
		check(groups.size() == 2 && "Main".equals(groups.get(0).name()) && groups.get(0).settings().size() == 2,
			"plain group lists its settings");
		SettingGroup extras = groups.get(1);
		check(extras.toggle() == on && extras.collapsedByDefault() && extras.settings().size() == 2,
			"a group can carry a switch and start folded");
		check(extras.children().size() == 1 && "Deep".equals(extras.children().get(0).name()), "groups nest");

		expectError("{\"modules\":{\"M\":{\"settings\":[{\"id\":\"a\",\"type\":\"nope\",\"name\":\"A\"}]}}}", "unknown type");
		expectError("{\"modules\":{\"M\":{\"settings\":[{\"id\":\"a\",\"type\":\"toggle\",\"name\":\"A\",\"default\":false},"
			+ "{\"id\":\"a\",\"type\":\"toggle\",\"name\":\"B\",\"default\":false}]}}}", "duplicate id");
		expectError("{\"modules\":{\"M\":{\"settings\":[],\"groups\":[{\"name\":\"G\",\"settings\":[\"ghost\"]}]}}}", "unknown setting");
		expectError("not json", "malformed json");
		try {
			spec.toggle("size");
			throw new AssertionError("asking for the wrong type must fail");
		} catch (IllegalStateException expected) {
			// typed access guards bindings between Kotlin code and data
		}
	}

	private static void expectError(String json, String what) {
		try {
			ModuleSettingsSpec.parse(json);
		} catch (IllegalArgumentException expected) {
			return;
		}
		throw new AssertionError("invalid data must be rejected: " + what);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
