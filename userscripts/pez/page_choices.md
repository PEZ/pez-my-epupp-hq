# Page choices

You use this on a page that runs Statsig. The panel lists the switches that page is holding, and you can keep a few of them set the way you want. The list lives in the page. The settings you change live in `localStorage`, and only those.

The script is [`page_choices.cljs`](page_choices.cljs). It runs at `document-start` on every page, because it has to wrap `JSON.parse` before the page reads its own bootstrap.

## Using the panel

A button sits at the bottom right. It opens the panel. Ctrl+F12 shows or hides it. The label under the close icon says `Show/hide with ^F12`.

Every header has a Pin control. Pinned rows are listed first, under **Pinned**, in the order you pinned them. The other sections follow, with whichever section has the most readable headers placed first. Inside a section, a header written in words comes before a stock label such as Control or Test, and a header that is only a number comes last.

Each row starts collapsed unless you had opened it before. Click a header to show that switch's settings, and click it again to hide them. A filter above the list narrows which headers you see. Matching headers stay collapsed until you click them.

Each header is an experiment. The status line counts experiments, configs, and overrides. While the filter has text, each count is shown as matching/total. A gate counts as one experiment and one config, because its only setting is the on/off switch. A config or a layer counts as one experiment, and each of its settings counts as one config. An experiment with a number in parentheses has overrides saved on it.

Change a setting and it is written into the page's Statsig data straight away. If the page has already read the old value, use **Reload**. **Put the page's switches back** clears every override.

A value that is an object or a list is shown in a disabled field, as JSON. The panel does not edit those. Headers with no settings are left out of the list.

## What is saved

`localStorage` key `pez.page-choices` holds only overrides:

```json
{ "1169094912": { "some_param": true } }
```

The id is the Statsig entry. The inner keys are setting names. A gate's only setting is `"value"`, a boolean.

A setting is stored when it differs from the value the page sent. Set it back to that value and the key is dropped. When an id has no settings left, the id is dropped. When nothing is overridden, the `localStorage` item is removed.

On read, keys whose names start with `__` are ignored, so stray metadata is not treated as a setting.

Pins and which headers are open are saved separately, under `pez.page-choices.panel`, for this site:

```json
{ "pins": ["1009060764"], "open": ["1009060764"] }
```

`pins` is the gate ids you pinned, newest first. `open` is every header you have expanded. Both are restored when the panel loads. The filter text is not saved.

The switch list and the original payload used to restore values before overrides are applied stay in the page atom. They are gone when the page reloads, and the list is read again.

## Where the list comes from

Statsig gives the page three buckets inside one values object:

| Bucket | In the panel | What a row is |
| --- | --- | --- |
| `feature_gates` | Gates | One on/off value |
| `dynamic_configs` | Configs | A bag of settings, titled with its group name when it has one, otherwise its id |
| `layer_configs` | Layers | The same shape as a config |

The first payload baked into the page is smaller than the store after Statsig's initialize response. The panel follows the largest payload seen this visit, so the list can grow once after load. A later payload that is the same size or smaller does not replace the list. The panel stays open or closed as you left it, and the filter and open headers stay put.

The clone of that payload is kept on `window.__pezPageChoicesWire`. It is the pre-override copy. It is not written to `localStorage`.

If that clone is missing (the hook was not installed before the payload was parsed), the panel uses whichever is larger of the live store and the bootstrap script.

## What happens when JSON is parsed

`JSON.parse` is wrapped once. The original parse is kept on `JSON.__pezOriginalParse`.

When the parsed value has `statsigPayload`, or is itself a values object with `feature_gates` and either configs or layers:

1. If this payload has more entries than any remembered this visit, it is cloned and remembered.
2. Saved overrides are written onto the object the page receives.
3. The panel reloads the list if the new payload is larger.

Other JSON is returned unchanged.

## What happens when you change a setting

The override map is updated, written to `localStorage`, and then honored:

1. Copy the remembered originals onto the live store (`window.__STATSIG__`, first instance).
2. Write the current overrides on top.

Copying the originals first means overrides do not pile up on themselves. Config and layer overrides also set `is_user_in_experiment` so the page treats those values as the ones in force. A gate override only sets `.value`.

The same id can exist in more than one bucket. An override for that id is written onto every bucket that has it.

## How the program is shaped

One atom, `!state`. The render loop is the only place that resets it. Actions decide the next state and which effects should run. Effects touch the DOM, `localStorage`, and the Statsig objects. The panel is Replicant hiccup, with the Epupp header from `epupp.ui`.

The actions you will meet in the code:

| Action | What it decides |
| --- | --- |
| `:page/ax.kick` | Install the parse hook, then look for Statsig |
| `:page/ax.loaded` | Accept a switch list when the page is not ready yet, or when the list is larger than the one showing |
| `:page/ax.saw-statsig` | After the first load, read the list again |
| `:page/ax.toggle` | Open or close one header |
| `:page/ax.query` | Set the filter text |
| `:setting/ax.commit` | Save one override and honor it |
| `:page/ax.restore` | Clear overrides and honor the empty map |
| `:page/ax.reload` | Reload the page on a short timeout, so this evaluation can finish |

Until Statsig shows up, the script polls every 200ms and gives up after 90 seconds.

## Reading a layer

A layer is a shared bag of settings. The header is the group this visit landed in, which is the name of that arm. The settings under it are every key in the bag. Many of those keys belong to the bag's current values. The ones this arm owns are `explicit_parameters` on the Statsig entry. The panel lists the whole bag, because that is what the page is reading.

Configs with no group name are still listed when they have settings. Their header is the id, which is usually a number.
