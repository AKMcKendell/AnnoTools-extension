# AnnoTools

AnnoTools is a [QuPath](https://qupath.github.io) extension for working with annotations across a whole project. Its eight tools each run over as many images as you choose, so you can move annotations between projects, build rings around them, find where classes overlap, classify them by eye, add or remove measurements, delete them by class and export their data to a spreadsheet.

Requires QuPath 0.7.

## The tools

| Tool | What it does |
| --- | --- |
| [Export or Import](#export-or-import) | Moves annotations in and out of project, keeping their hierarchy/data |
| [Make rings](#make-rings) | Builds concentric rings around annotations at distances you set |
| [Find colocalization](#find-colocalization) | Makes new annotations where annotations of two classes overlap |
| [Manual classification](#manual-classification) | Lets people classify annotations by eye, then review where they disagree |
| [Add measurements](#add-measurements) | Adds intensity and shape measurements to annotations |
| [Remove measurements](#remove-measurements) | Deletes chosen measurements and metadata from annotations |
| [Remove annotations](#remove-annotations) | Deletes annotations of chosen classes, with everything inside them |
| [Save data](#save-data) | Exports annotation details, measurements and metadata to a CSV file |

## Installation

1. Download `AnnoTools-<version>.jar` from the [latest release](https://github.com/AKMcKendell/AnnoTools-extension/releases/latest). You don't need the `-sources` or `-javadoc` jars.
2. Drag the jar onto the QuPath window. QuPath copies it into the `extensions` folder of your QuPath user directory, and asks you to set up that directory first if you don't have one.
3. Restart QuPath.

To update, replace the old jar in the `extensions` folder with the new one, then restart QuPath. You can open the folder from **Extensions › Manage extensions**.

## Getting started

1. Open a project. The tools work across a project's images, so they won't start without one.
2. Pick a tool from the **AnnoTools** button on the toolbar (the cubes icon) or from **Extensions › AnnoTools**. In the toolbar menu, hover over a tool to see what it does.
3. Choose your images, classes and options, then press the button at the bottom of the dialog to run it.

AnnoTools works on annotations. Detections are left alone, except that Remove annotations also deletes anything nested inside the annotations it removes.

## Features shared by the tools

These apply to every tool except Manual classification, which works differently (see [its section](#manual-classification)).

- **Choosing images and classes.** Tick them in the lists. Click a box, then shift-click another, and every box between them is set to match the one you clicked first. The box at the top of a list selects or clears all of it, and **Current Image** picks the image open in the viewer. Each class shows how many annotations it has in the selected images and in the whole project, such as `Vessel [12 / 40]`. Annotations without a class are listed as `Unclassified`.
- **Spotting problems before you run.** A selected image the tool would skip, such as one with no annotations of the chosen classes, is outlined in red; hover over it to see why. If the run button is greyed out, hover over it to see what's missing, and the lists holding it up are outlined in red.
- **Remembered choices.** Each tool remembers your last choices for each project, in an `AnnoTools-Data` folder inside the project folder.
- **Open images.** Tools save each image they change. If an image is open in a viewer, including in a split view, the tool works on what's on screen and the viewer shows the result. Any unsaved edits you'd made to that image are saved along with the tool's changes.
- **Results from earlier runs.** If Make rings or Find colocalization would add annotations to images that already have some from an earlier run, or an import would add classes an image already has, the tool lists those images. It then asks whether to keep the existing annotations, remove them first, or skip those images.
- **Deleting is permanent.** Remove annotations, Remove measurements and the remove option above can't be undone, so back up your project first if you're unsure.
- **Progress and stopping.** A progress window shows the image, step and elapsed time. **Stop** ends the run after the image it's working on. Add measurements, Find colocalization and Make rings can also **Stop now**, which stops straight away without saving the image it was working on. If that image is open, its partial changes stay on screen, unsaved.
- **Errors.** If a run hits an error, it stops and a notification names the image it was on. The full details are in QuPath's log (**View › Show log**).

## The tools in detail

### Export or Import

Moves annotations between images or projects using GeoJSON files in a folder you choose, one file per image, named after it (`<image name>.geojson`). Pick a mode at the top of the dialog:

- **Export** writes each selected image's annotations of the chosen classes to the folder. If a file already exists, you can overwrite it or skip that image. If an image has nothing to export, you can skip it or write an empty file.
- **Import** adds every object in each selected image's file to that image. If a file can't be found, you can skip the image or browse for the file.
- **Nest** rebuilds the hierarchy of annotations already in the images, using the parent details saved when they were exported. No files are read or written.

**Include Parents** keeps the hierarchy intact across the move. On export it saves each annotation's parent in its metadata (`Parent_ID` and `Parent_Name`), and on import it nests annotations back under those parents. It starts ticked every time. The **Folder check** line tells you how many of the selected images already have a file in the folder (when exporting) or are missing one (when importing). Imported annotations are locked.

### Make rings

Builds concentric rings around each annotation of the chosen classes, at up to five distinct whole-number distances in µm, such as `6,16,31`.

- Each ring is the band between its distance and the one before it; the first ring starts at the annotation's edge. Rings are named after the class of the central object (`Vessel`) and their outer distance from the central object: `Vessel_6`, `Vessel_16`, `Vessel_31`.
- Rings never overlap the annotations they're built around, or any closer ring, including those around neighboring annotations. Rings of neighboring annotations at the same distance can overlap each other.
- **Nest new rings?** puts each ring under its central annotation (the default), under the top object within the central object's hierarchy, or not nested at all.
- **Only expand outward** (on by default) fills any holes in the annotation first, so rings don't grow into them.
- **Constrain to parent** (on by default) trims rings so they do not extend beyond the parent of the central object.
- **Simplify geometry** (off by default) simplifies very detailed shapes before expanding them. It's faster, but the rings are less exact. The slider sets how detailed a shape can be (2,000 to 10,000 vertices) before it's simplified.
- The new rings, and the annotations they're built around, are locked.

### Find colocalization

Makes a new annotation wherever an annotation from **Classes 1** overlaps one from **Classes 2**.

- Every pairing of the two lists is made. For example, `Vessel` in **Classes 1** with `Astrocyte` and `Aβ` (Amyloid-β) in **Classes 2** gives new `Vessel-Astrocyte` and `Vessel-Aβ` annotations.
- **Nest new objects?** puts each new annotation under its **Classes 1** annotation (the default), under the outermost object that annotation sits in, or not nested at all.
- Selected images that don't have both class groups are skipped.

### Manual classification

Lets one or more users classify annotations by eye, then lets a reviewer settle the ones they disagree on. Each person's calls are saved separately in the annotations' metadata, so classifiers work independently and never overwrite each other.

It works through the project's images that have annotations of the chosen class, in random order, starting with the open image if it still needs completion. Images you've finished are skipped, so you can stop and pick up later; **Check work** goes back through the finished ones instead, in project order. Before it starts, it lists any images it can't use, such as ones with no annotations of that class, and lets you carry on with the rest. At the end of each image it asks whether to save and move on or review/change the classifications you made.

**Setting up**

- **Classifier Mode** or **Reviewer Mode**.
- **Classifier** or **Reviewer**: choose your name from the list. Add a new name with **+** (letters and numbers, up to 15 characters), or use the search button to fill the list with names already used among the existing objects in the project.
- **Annotation class**: the annotations to classify.
- **Classifying name**: what's being judged, such as `CAA` (cerebral amyloid angiopathy, Aβ in blood vessels). Each call is saved under a metadata key built from the mode, the person and this name, such as `AT-manual_classifier_User1_CAA`.
- **Grid size (µm)**: classifier mode works through each image one grid square at a time.
- **Channels**: up to five channels to switch on and off while classifying.
- **Classes/Colors**: two to five classes, each with a color (`false`, `true` and `maybe` by default, or type your own). Annotations start in the first class, shown in a separate "unconfirmed" color until you save them. This is also where you choose the two or three classes a reviewer can give.
- **Reviewer annos**: which annotations a reviewer sees, out of those every classifier has finished. You can pick ones the classifiers disagree on, ones any of them put in a given class, or ones all of them did. By default it's disagreements, plus anything anyone marked `maybe`.
- The setup can be exported to a `.json` file and imported elsewhere, so everyone on a study uses the same settings. This can be paired with [Export or Import](#export-or-import) to share the annotations with the added user classifications.  

**Controls** (press **I** at any time to show or hide them on screen)

| Key or mouse | Action |
| --- | --- |
| *Classifier mode* | |
| Left click an annotation | Change its class (cycles through them) |
| Right click an annotation | Zoom in on it, or back out |
| **N** or mouse forward button | Save this grid square and go to the next |
| **B** or mouse back button | Go back to the previous grid square |
| **R** | Clear your classes in this grid square |
| *Reviewer mode* | |
| **6**, **7**, **8** | Give the annotation that class and go to the next |
| **B** or mouse back button | Go back to the previous annotation |
| *Both modes* | |
| **1**–**5** | Switch a channel on or off |
| Scroll | Change the brightness of the channel marked "scroll" |
| **A** or middle click | Show or hide annotations |
| Hold **Shift** | Enter or leave free view (below) |
| **C** or **Esc** | Cancel |

Free view lets you look around the image, which is otherwise constrained to the group being classified: drag to pan, right click an annotation to zoom in on it, press **G** to show or hide the grid, and tap **Shift** to switch scrolling between zoom and brightness. The summary buttons show how many annotations are in each class across the image; click one to highlight those annotations.

### Add measurements

Adds intensity and shape measurements to every annotation of the chosen classes.

- **Intensity measurements**: choose channels (the ones every image in the project shares) and statistics (mean, median, minimum, maximum, standard deviation and variance). Annotations are measured at full resolution with QuPath's own intensity measurements. Very large ones are measured in tiles instead, which computes the same statistics without running out of memory; only an annotation whose bounding box tops about a billion pixels is measured at reduced resolution. Columns are named the way QuPath names them (`DAPI: Mean`), using each channel's base name, so they are aligned on export using either [Save data](#save-data) or QuPath's built-in summary tables.
- **Morphology measurements**: QuPath's shape features, such as area, length, circularity, solidity, and maximum and minimum diameter.

### Remove measurements

Deletes the chosen measurements and metadata entries from annotations of the chosen classes, in the chosen images. The lists only show what's on annotations of the selected classes.

To keep the dialog quick, the lists come from the last scan of the project, and the dialog shows when that was. Press **Refresh project data** to scan again after adding measurements or metadata, for example with Add measurements. The scan reads saved data, so save open images first. You're asked to confirm before anything is deleted.

### Remove annotations

Deletes every annotation of the chosen classes from the chosen images, along with everything nested inside them. You're asked to confirm first.

### Save data

Writes a CSV file with one row per annotation of the chosen classes, across the chosen images.

- Every row has `Image`, `Anno_Name`, `Anno_Class`, `Anno_ID`, `Anno_ID_Path` (the IDs from the outermost parent down to this annotation), `Anno_Area_um` (area in µm²), `Anno_Hierarchy_Level`, `Parent_Name`, `Parent_Class` and `Parent_ID`.
- After those come a `Measurements_<name>` column for each measurement you choose, and a `Metadata_<key>` column for each metadata entry, such as Manual classification results.
- Columns that are empty in every row are left out, and any other missing value is a blank cell.

As in [Remove measurements](#remove-measurements), the lists come from the last scan of the project, with **Refresh project data** to update it. If the CSV is open in another program, such as Excel, you're asked to close it and try again. Stopping the run writes no file.

## Building from source

```bash
./gradlew build
```

The extension jar is written to `build/libs/AnnoTools-<version>.jar` (on Windows, run `gradlew.bat build`).

## Help and feedback

Report a problem or suggest an improvement on the [issues page](https://github.com/AKMcKendell/AnnoTools-extension/issues).
