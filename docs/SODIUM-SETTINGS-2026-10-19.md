# Sodium video settings

gysf 0.7.4 adds a `gysf` section to Sodium's video settings, tested with
Sodium 0.9.3-beta.1+mc26.3 on the native Vulkan backend. Five pages expose 70 rows.
Water, fog, lighting, sky, entities, POM, exposure, tonemapping, HDR, streaming and
debug controls use Sodium widgets. DLSS/upscaler, frame generation, SHaRC and
cloud-style controls open the existing capability-aware sub-screens. The advanced
page also opens the complete settings hub, including reset and optional LOD actions.

The optional `sodium:config_api_user` entrypoint uses a compile-only dependency;
Sodium is neither required nor bundled. Without Sodium the existing Video Settings
button still opens the RT hub.

Native controls reuse `RtVideoOptions` validation, units, translated labels and
callbacks. They save through Sodium's apply/storage hooks. Default widget values
are computed without mutating live settings. Returning from a sub-screen reloads
bindings, preserves unrelated pending edits, and accepts newer external changes.
The global reset now also includes fog settings and biome fog tint.

## Icon

`assets/caustica/icon-gysf.png` is shared by Fabric metadata and Sodium's untinted
icon; a neutral gray theme keeps the sidebar monochrome. The image was generated
with the built-in image tool using the Voxy Vulkan icon as a style reference.
Prompt: a bold rounded italic lowercase `g` over a sparse thick grid, silver/white
on charcoal black, shallow dark outline, neutral grayscale, no other text, readable
at sidebar size. The project stores the final PNG, not a dependency on its generation path.

## Validation

The isolated `-Dgysf.smoke.settings=true` client checks entrypoint/page/icon
registration, pending edits, undo, apply/TOML persistence, reopen, factory defaults,
degree/radian and tenths conversions, non-contiguous debug choices, sub-screen
return, global reset and retained pending edits. A second run without Sodium checks
the original RT/water navigation and then renders a copied world. Neither run
opens the original player save for writing.

Evidence: `artifacts/2026-10-19/sodium-settings/`, including screenshots, Vulkan
validation logs, package hashes, deployment receipt and protected profile hashes.
These checks establish settings integration and a short render replay; they do
not establish a fix for the separately reported missing LODs after world re-entry.
