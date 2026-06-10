/*
    Developed by Alec K. McKendell
    2026.4.20
*/


// Class for nesting option strings
class NestStrings {
    static final String CLASS1        = "Under class 1 object"
    static final String CLASS2        = "Under class 2 object"
    static final String CLASS1_PARENT = "Under class 1 object parent"
    static final String CLASS2_PARENT = "Under class 2 object parent"
    static final String NONE          = "None"
}


// --- FUNCTIONS ---
// Function: Helper to run on FX thread
def runOnFxThreadAndWait( Closure fxCode ) {
    def latch = new CountDownLatch( 1 )
    def result = [value: null]
    Platform.runLater {
        try {
            result.value = fxCode.call()
        } finally {
            latch.countDown()
        }
    }
    latch.await()
    return result.value
}

// Function: Assign or update tooltips
def setTT( objs, String tooltip ) {
    objs.each { obj ->
        if (obj instanceof Control) {
            obj.getTooltip() ? obj.getTooltip().setText( tooltip ) : obj.setTooltip( new Tooltip( tooltip ) )
        } else {
            def currentTT = obj.getProperties().get( "tooltip" )
            if (currentTT) {
                currentTT.setText( tooltip )
            } else {
                def newTT = new Tooltip( tooltip )
                Tooltip.install( obj, newTT )
                obj.getProperties().put( "tooltip", newTT )
            }
        }
    }
}

// Function: Create window showing script progress
def createProgressWindow( String title, int totalImages ) {
    return runOnFxThreadAndWait {
        def stage = new Stage()
        stage.setTitle( title )
        
        def imageLabel = new Label( "Image:" )
        def imageName = new Label( "Starting..." )
        def imageRow = new HBox( 5, imageLabel, imageName )
        
        def bar = new ProgressBar( 0.0 )
        bar.setPrefWidth( 350 )
        def progressLabel = new Label( "0 / ${totalImages}" )
        def barRow = new HBox( 10, bar, progressLabel )
        
        def stepLabel = new Label( "Step:" )
        def stepUpdate = new Label()
        def stepProgress = new Label()
        def stepRow = new HBox( 5, stepLabel, stepUpdate, stepProgress )
        
        def cancelBtn = new Button( "Cancel" )
        cancelBtn.setDisable( false )
        def spacer = new Region()
        HBox.setHgrow( spacer, Priority.ALWAYS )
        def timerLabel = new Label( "Elapsed: 00:00:00" )       
        def bottomRow = new HBox( 10, cancelBtn, spacer, timerLabel )
        bottomRow.setAlignment( Pos.CENTER_LEFT )
        
        List rows = [imageRow, barRow, stepRow, bottomRow]
        
        def vbox = new VBox( 10 )
        vbox.getChildren().addAll( rows )
        vbox.setPadding( new Insets( 10 ) )
        vbox.setAlignment( Pos.CENTER_LEFT )
        
        // Sizing to fit all rows
        def updateMinWidth = {
            double preferred = rows.collect { it.getWidth() }.max() + vbox.getPadding().getLeft() + vbox.getPadding().getRight()
            if ( preferred > stage.getWidth() ) {
                stage.setWidth( preferred )
            }
        }
        
        // Attaching the width update as a listener
        rows.each { node ->
            node.widthProperty().addListener { obs, old, nw -> updateMinWidth() }
        }
        
        def scene = new Scene( vbox )
        stage.setScene( scene )
        
        def gui = QuPathGUI.getInstance()
        if (gui != null) {
            stage.initOwner( gui.getStage() )
        }
        stage.initModality( Modality.WINDOW_MODAL )
        stage.show()
        
        def startTime = System.nanoTime()
        
        def timeline = new Timeline(
            new KeyFrame(
                javafx.util.Duration.seconds( 1 ),
                { evt ->
                    long elapsedNs = System.nanoTime() - startTime
                    long seconds = (long)( elapsedNs / 1_000_000_000L )
                    long hrs = seconds / 3600
                    long mins = (seconds % 3600) / 60
                    long secs = seconds % 60
                    
                    timerLabel.setText(
                        String.format( "Elapsed: %02d:%02d:%02d", hrs, mins, secs )
                    )
                }
            )
        )
        timeline.setCycleCount( Animation.INDEFINITE )
        timeline.play()
        
        // Signal to cancel script
        def cancelFlag = new java.util.concurrent.atomic.AtomicBoolean( false )
        def cancelUpdateLabel = new Label( "Cancelling after image..." )
        cancelUpdateLabel.setPadding( new Insets( 4 ) )
        cancelBtn.setOnAction {
            bottomRow.getChildren().set(
                bottomRow.getChildren().indexOf( cancelBtn ), cancelUpdateLabel
            )
            cancelFlag.set( true )
        }
        stage.setOnCloseRequest { evt ->
            cancelFlag.set( true )
        }
        
        return [
            stage        : stage,
            imageName    : imageName,
            bar          : bar,
            progressLabel: progressLabel,
            stepUpdate   : stepUpdate,
            stepProgress : stepProgress,
            cancelFlag   : cancelFlag,
            timeline     : timeline
        ]
    }
}

// Function: Make a checkbox that selects/deselects all checkboxes
def makeSelectAllCB( List groupCBs ) {
    def selectAllCB = new CheckBox()
    
    // Select / deselect all action
    selectAllCB.setOnAction {
        selectAllCB.setIndeterminate( false )
        groupCBs.each { it.setSelected( selectAllCB.isSelected() ) }
    }
    
    // Update relevant labels/instructions when boxes are clicked
    groupCBs.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal ->
            int numCBs = groupCBs.size()
            int numSelected = groupCBs.count { it.isSelected() }
            
            if (numSelected !in [0, numCBs]) {
                selectAllCB.setIndeterminate( true )
            
            } else {
                selectAllCB.setIndeterminate( false )
                selectAllCB.setSelected( numSelected > 0 )
            }
        }
    } 
    
    return selectAllCB
}


// Function: Make a box with checkboxes and a modifiable header
def makeCheckboxBox( String title, String keyWord = null, List objects, int prefWidth = 0, int prefHeight = 0 ) {
    
    def checkboxBox = new VBox( 5 )
    checkboxBox.setPadding( new Insets( 5 ) )
    if (prefWidth > 0) {
        checkboxBox.setPrefWidth( prefWidth )
    }
    if (prefHeight > 0) {
        checkboxBox.setPrefHeight( prefHeight )
    }
    
    def checkboxContainer = new VBox( 5 )
    checkboxContainer.setPadding( new Insets( 5 ) )
    
    // Build checkboxes from supplied objects
    def checkboxes = objects.collect { obj ->
        
        def cb = new CheckBox( obj.name )
        cb.setUserData( obj.data ?: obj.name )
        cb.setMnemonicParsing( false ) // so no characters are dropped
        
        return cb
    }
    
    checkboxes.each { checkboxContainer.getChildren().add( it ) }
    
    def selectAllCB = makeSelectAllCB( checkboxes )
    
    def scrollPane = new ScrollPane( checkboxContainer )
    scrollPane.setFitToWidth( true )
    
    def titleLabel = new Label()
    titleLabel.setPadding( new Insets( 5, 0, 0, 0 ) )
    
    // Add listeners to checkboxes (for changing instructions/labels)
    checkboxes.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal ->            
            
            String TTword = keyWord ?: title
            
            def selectedNow = checkboxes.findAll { it.isSelected() }.collect { it.getText() }
            titleLabel.setText( "${title.capitalize()} [${selectedNow.size()}]" )
            
            selectAllCB.setText( "${title.capitalize()} [${selectedNow.size()}]" )
            setTT( selectAllCB, selectAllCB.isSelected() ? "Deselect all ${TTword.uncapitalize()}" : "Select all ${TTword.uncapitalize()}" )
            
            String tooltipText = "Select ${TTword.uncapitalize()}"
            
            if (selectedNow.size() > 0) {
                tooltipText = "${(keyWord ?: title).capitalize()} selected:"
                selectedNow.eachWithIndex { item, i ->
                    tooltipText += "\n ${i+1}. ${item}"
                }
            }
            
            setTT( [titleLabel, scrollPane], tooltipText )
        }
        
        // Double fire to spark listeners
        cb.setSelected( !cb.isSelected() )
        cb.setSelected( !cb.isSelected() )
    }
    
    def clearBtn = new Button( "Clear" )
    clearBtn.setOnAction { checkboxes.each { it.setSelected( false ) } }
    setTT( clearBtn, "Clear selected items" )
    
    def topRow = new HBox( 5 )
    topRow.setPadding( new Insets( 8 ) )
    
    def topRowSpacer = new Region()
    HBox.setHgrow( topRowSpacer, Priority.ALWAYS )
    
    
    checkboxBox.getChildren().addAll( topRow, scrollPane )
    
    checkboxBox.setStyle( "-fx-border-color: #000000" )  
    topRow.setStyle( "-fx-border-color: #000000; -fx-border-width: 0 0 1 0" )    
    scrollPane.setStyle( "-fx-background-color: transparent" )
    
    return [
        box      : checkboxBox,
        header   : topRow,
        selectAll: selectAllCB,
        title    : titleLabel,
        spacer   : topRowSpacer,
        clear    : clearBtn,
        CBs      : checkboxes
    ]
}

// Function: Final safety wrapper for ROI conversion. Uses buffer(0) only if the cleaned geometry is invalid
def cleanForROI( Geometry g ) {
    if (g == null || g.isEmpty()) {
        return g?.getFactory()?.createPolygon()
    }

    if (g instanceof Polygon || g instanceof MultiPolygon) {
        return g.isValid() ? g : g.buffer(0)
    }

    def polygonals = PolygonalExtracter.getPolygonals( g )
    if (polygonals == null || polygonals.isEmpty()) {
        return g.getFactory().createPolygon()
    }

    def result = polygonals.size() == 1 ? polygonals[ 0 ] : UnaryUnionOp.union( polygonals )

    // Fallback only if invalid
    if (result != null && !result.isValid()) {
        result = result.buffer( 0 )
    }

    return result
}



// Project & preferences file
def project = getProject()
def currentImageData = getCurrentImageData()
def currentEntry = currentImageData ? project.getEntry( currentImageData ) : null
def projectDirectory = project.getBaseDirectory()

// Folder and file names of where preferences are stored in directory
def scriptPrefsFolderName = "Scripts_Preferences"
def scriptPrefsFileName   = "ColocalizationTool_Preferences" + ".json"

def prefsFolder = new File( projectDirectory, scriptPrefsFolderName )
if (!prefsFolder.exists()) { prefsFolder.mkdirs() }

def prefsFile = new File( prefsFolder, scriptPrefsFileName )
if (!prefsFile.exists()) { prefsFile.createNewFile() }

// Read previous preferences
def prePrefsMap = [:]
if (prefsFile.length() > 0L && prefsFile.getText() != "null") {
    def inputGson = new Gson()
    prePrefsMap = inputGson.fromJson( prefsFile.getText(), Map )
}


// --- PREFERENCES POPUP ---
def codePreferences = runOnFxThreadAndWait {
    
    def dialog = new Dialog()
    dialog.setTitle( "Colocalization tool" )
    
    def root = new BorderPane()
    root.setPrefHeight( 350 )
    
    dialog.getDialogPane().buttonTypes.addAll( ButtonType.OK, ButtonType.CANCEL )
    def okBtn = dialog.getDialogPane().lookupButton( ButtonType.OK )
    
    
    // TOP ROW: Nesting checkbox
    def nestDropdownLabel = new Label( "Nest new objects?" )
    nestDropdownLabel.setPadding( new Insets( 5 ) )
    
    def nestDropdown = new ComboBox()
    def nestOptions = [ NestStrings.CLASS1,
                        NestStrings.CLASS1_PARENT,
                        NestStrings.NONE ]
    nestDropdown.getItems().addAll( nestOptions )
    setTT( [nestDropdownLabel, nestDropdown], "Where should new objects be nested?" )
    nestDropdown.setValue( prePrefsMap.nestBehavior in nestOptions ? prePrefsMap.nestBehavior : nestOptions[0] )
    
    def topSpacer = new Region()
    HBox.setHgrow( topSpacer, Priority.ALWAYS )
    
    def topRow = new HBox( 5 )
    topRow.setPadding( new Insets( 10 ) )
    topRow.getChildren().addAll( topSpacer, nestDropdownLabel, nestDropdown )
    
    root.top = topRow
    
    
    // LEFT PANEL: Image Selection
    def imageNames = project.getImageList().collect { it.getImageName() }
    def imagesBox = makeCheckboxBox( "Select images", "images", imageNames.collect { [name: it] } )
    def currentImageCB = new CheckBox( "Current Image" )
    imagesBox.header.getChildren().addAll( imagesBox.selectAll, imagesBox.spacer, currentImageCB )
    imagesBox.CBs.each { it.setSelected( it.getText() in prePrefsMap.selectedImages  ) }
    BorderPane.setMargin( imagesBox.box, new Insets( 0, 15, 0, 0 ) )
    
    if (currentEntry) {
        def currentCB = imagesBox.CBs.find { it.getText() == currentEntry.getImageName() }
        currentImageCB.selectedProperty().bindBidirectional(
            currentCB.selectedProperty()
        )
        
        // If current image was only one selected, will update to new image
        if (prePrefsMap.currentImage == true && prePrefsMap.selectedImages.size() == 1) {
            imagesBox.CBs.each { it.setSelected( false ) }
            currentImageCB.setSelected( true )
        }
    } else {
        currentImageCB.setDisable( true )
    }
    
    // Setting and updating tooltip for current image checkbox
    currentImageCB.selectedProperty().addListener { obs, oldVal, newVal ->
        setTT( currentImageCB, currentImageCB.isSelected() ? "Deselect current image" : "Select current image" )
    }
    
    root.left = imagesBox.box
    
    
    // BOTTOM PANEL: Output class preview
    def bottomSpacer = new Region()
    HBox.setHgrow( bottomSpacer, Priority.ALWAYS )
    def outputPreviewLabel = new Label( "Output class preview:" )
    outputPreviewLabel.setPadding( new Insets( 5 ) )
    def class1Prev = new SimpleStringProperty( "Class1" )
    def class2Prev = new SimpleStringProperty( "Class2" )
    def outputPreview = new Label( "${class1Prev.get()}-${class2Prev.get()}" )
    outputPreview.setPadding( new Insets( 5 ) )
    
    class1Prev.addListener { obs, oldVal, newVal ->
        outputPreview.setText( "${class1Prev.get()}-${class2Prev.get()}" )
    }
    
    class2Prev.addListener { obs, oldVal, newVal ->
        outputPreview.setText( "${class1Prev.get()}-${class2Prev.get()}" )
    }
    
    def bottomRow = new HBox( 5 )
    bottomRow.setPadding( new Insets( 10 ) )
    bottomRow.getChildren().addAll( bottomSpacer, outputPreviewLabel, outputPreview )
    
    root.bottom = bottomRow
    
    
    // RIGHT PANELS: PathClass selection list
    def viewer = getCurrentViewer()
    def annos  = viewer ? getAnnotationObjects() : []
    def projectPathClasses = project.getPathClasses().findAll { it?.getName() } // No null (those with a name)
    def countsByClass = annos.countBy { it.getPathClass() } 
    
    // Organizing any pathclasses with objects in the open viewer to the top of the list
    def highCounts = []
    def lowCounts = []
    projectPathClasses.each { pc ->
        def entry = [name: pc.getName(), num: countsByClass[ pc ] ?: 0]
        (entry.num > 0 ? highCounts : lowCounts) << entry
    }
    highCounts.sort { -it.num } // Sort for descending abundance
    def pathClassNamesAndNum = highCounts + lowCounts
    
    List pathClassCBsMap = pathClassNamesAndNum.collect { [name: (it.num > 0 ? "${it.name} [${it.num}]" : it.name), data: it.name] }
    
    def pathClassBox1 = makeCheckboxBox( "Classes 1", "overlap class 1", pathClassCBsMap, 200 )
    pathClassBox1.header.getChildren().addAll( pathClassBox1.title, pathClassBox1.spacer, pathClassBox1.clear )    
    
    def pathClassBox2 = makeCheckboxBox( "Classes 2", "overlap class 2", pathClassCBsMap, 200 )    
    pathClassBox2.header.getChildren().addAll( pathClassBox2.title, pathClassBox2.spacer, pathClassBox2.clear )    
    
    // Adding listeners to stop same class from being selected twice and updating class preview
    pathClassBox1.CBs.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, selected ->
            def match = pathClassBox2.CBs.find { it.getUserData() == cb.getUserData() }
            match.setDisable( selected )
            
            def firstSelected = pathClassBox1.CBs.find { it.isSelected() }
            class1Prev.set( selected ? cb.getUserData() : firstSelected ? firstSelected.getUserData() : "Class1" )
        }
    } 
    pathClassBox2.CBs.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, selected ->
            def match = pathClassBox1.CBs.find { it.getUserData() == cb.getUserData() }
            match.setDisable( selected )
            
            def firstSelected = pathClassBox2.CBs.find { it.isSelected() }
            class2Prev.set( selected ? cb.getUserData() : firstSelected ? firstSelected.getUserData() : "Class2" )
        }
    }
    
    // Pre-selecting classes
    pathClassBox1.CBs.each { it.setSelected( it.getUserData() in prePrefsMap.objectClasses1 ) }
    pathClassBox2.CBs.each { it.setSelected( it.getUserData() in prePrefsMap.objectClasses2 ) }
    
    def pathclassBoxes = new HBox( 10, pathClassBox1.box, pathClassBox2.box )
    root.center = pathclassBoxes
    
    
    
    // Disabling the "OK" button if not all inputs are met    
    def anySelectedImage = Bindings.createBooleanBinding(
        { imagesBox.CBs.any { it.isSelected() } },
        *imagesBox.CBs.collect { it.selectedProperty() }
    )
    def anySelectedClass1 = Bindings.createBooleanBinding(
        { pathClassBox1.CBs.any { it.isSelected() } },
        *pathClassBox1.CBs.collect { it.selectedProperty() }
    )
    def anySelectedClass2 = Bindings.createBooleanBinding(
        { pathClassBox2.CBs.any { it.isSelected() } },
        *pathClassBox2.CBs.collect { it.selectedProperty() }
    )
    
    okBtn.disableProperty().bind(
        anySelectedImage.not()
        .or( anySelectedClass1.not() )
        .or( anySelectedClass2.not() )
    )
    
    
    // Attach root & show dialog
    dialog.getDialogPane().content = root
    
    def result = dialog.showAndWait()
    def button = result.get()
    
    if (button == ButtonType.OK) {                
        def selectedImages = imagesBox.CBs
                .findAll { it.isSelected() }
                .collect { it.getText() }
                
        List<String> selectedClasses1 = pathClassBox1.CBs.findAll { it.isSelected() }.collect { it.getUserData() }
        List<String> selectedClasses2 = pathClassBox2.CBs.findAll { it.isSelected() }.collect { it.getUserData() }
        
        // Return all preferences of "OK" is selected
        return [
            selectedImages : selectedImages,
            currentImage   : currentImageCB.isSelected(),
            objectClasses1 : selectedClasses1,
            objectClasses2 : selectedClasses2,
            nestBehavior   : nestDropdown.getValue()
        ]
        
    } else {
        return null
    }
}

// Exit if cancelling
if (codePreferences == null) {
    return
    
} else { // Write to .json if exporting/importing
    
    def outputGson = new GsonBuilder().setPrettyPrinting().create()
    prefsFile.text = outputGson.toJson( codePreferences )
}


def imagesToRun = project.getImageList().findAll { it.getImageName() in codePreferences.selectedImages }

def total = imagesToRun.size()
def progressUI = createProgressWindow( "Processing", total )
def cancelFlag = progressUI.cancelFlag
int processed = 0

// Closure to show update on code progress
def updateProgressWindow = { Map args = [:] ->
    String image      = args.image
    String stepUpdate = args.stepUpdate
    String stepFrac   = args.stepFrac
    double imageFrac  = args.imageFrac ?: 0
    String imageStep  = args.imageStep

    Platform.runLater {
        if (image)      { progressUI.imageName.setText( image ) }
        if (stepUpdate) { progressUI.stepUpdate.setText( stepUpdate ) }
        if (stepFrac)   { progressUI.stepProgress.setText( stepFrac ) }
        if (imageFrac)  { progressUI.bar.setProgress( imageFrac ) }
        if (imageStep)  { progressUI.progressLabel.setText( imageStep ) }
    }
}

imageLoop:
for (image in imagesToRun) {
    
    if (cancelFlag.get()) break
    
    // Reporting current image
    updateProgressWindow( image: image.toString(), stepFrac: "(0%)" )
    
    // If working on current image, modifying respective image data
    def imageData = image == currentEntry ? currentImageData : image.readImageData()
    def hierarchy = imageData.getHierarchy()
    def annotations = hierarchy.getAnnotationObjects()
    
    int netSteps = codePreferences.objectClasses1.size() * codePreferences.objectClasses2.size()
    int step = 1
    
    // --- EXECUTION ---
    class1Loop:
    for (class1 in codePreferences.objectClasses1) {
        
        class2Loop:
        for (class2 in codePreferences.objectClasses2) {
            
            String newClass = class1 + "-" + class2 
            updateProgressWindow( stepUpdate: "Working on ${newClass}" )
            
            // Adding the new class to the pathclass list if it is not already on it
            def availableClasses = getQuPath().getAvailablePathClasses()               
            if (!availableClasses.contains( getPathClass( newClass )) ) {
                availableClasses << getPathClass( newClass )
            }               
       
            // Clearing objects if they already exist to not double add
            def oldObjects = annotations.findAll {
                it.getPathClass()?.getName() == newClass
            }
            hierarchy.removeObjects( oldObjects, false )
            
            //Setting the color of the new class
            int color1 = getPathClass( class1 ).getColor()
            int color2 = getPathClass( class2 ).getColor()
            int newClassColor = ((color1 + color2) / 2)
            getPathClass( newClass ).setColor( newClassColor )
            
                
            // Getting objects from each class
            def class1Annos = annotations.findAll {
                it.getPathClass()?.getName() == class1
            }
            def class2Annos = annotations.findAll {
                it.getPathClass()?.getName() == class2
            }
            
            // Skipping the loop if either class has no objects
            if (class1Annos.isEmpty() || class2Annos.isEmpty()) continue
            
            class1Annos.each { class1Anno ->
                def class1Geom = class1Anno.getROI().getGeometry()
                
                class2Annos.each { class2Anno ->
                    def class2Geom = class2Anno.getROI().getGeometry()
                    
                    if (class1Geom.intersects( class2Geom )) {
                        
                        // Establishing the variable for the coming lines
                        def overlapGeom = cleanForROI( class1Geom.intersection( class2Geom ) )
                        
                        if (!overlapGeom.isEmpty() && overlapGeom.getArea() > 1) { 
                            
                            def overlapROI = GeometryTools.geometryToROI( overlapGeom, class1Anno.getROI().getImagePlane() )
                            
                            // Making the overlapping geometry into an annotation object
                            def overlapObj = PathObjects.createAnnotationObject( overlapROI, getPathClass( newClass ) )
                            
                            // Inserting the annotation based on user preference
                            def parent
                            switch (codePreferences.nestBehavior) {
                                case (NestStrings.CLASS1):
                                    parent = class1Anno
                                    break
                                
                                case (NestStrings.CLASS1_PARENT):
                                    parent = class1Anno.getParent()
                                    break
                                    
                                case (NestStrings.NONE):
                                    parent = hierarchy.getRootObject()
                                    break
                            }
                            
                            hierarchy.addObjectBelowParent( parent, overlapObj, false )
                            overlapObj.setLocked( true )
                        }
                    } 
                }
            }
            int percentDone = ( step / netSteps ) * 100
            updateProgressWindow( stepFrac: "(${percentDone}%)" )
            step ++
        }
    }
    
    
    // Updating viewer and saving (when relevant)
    // If only current image is imported to, will not automatically save so the import can be tested
    if (imagesToRun.size() > 1 || image != currentEntry) {
        image.saveImageData( imageData ) // Saving data
    }
    
    // Ensure current viewer updates its annotation list when working on the current image
    if (image == currentEntry) {
        runOnFxThreadAndWait {
            def viewer = getCurrentViewer()
            if (viewer != null) {
                // Re-assign the same ImageData to force a UI refresh
                imageData.getHierarchy().fireHierarchyChangedEvent( this )
                viewer.setImageData( imageData )
            }
        }
    }
    
    // Closing background imagedata for non-current image
    if (image != currentEntry) {
        imageData.close()
    }
    
    
    // - Reporting progress -
    processed++
    double frac = processed / total 
    updateProgressWindow( imageFrac: frac, imageStep: "${processed}/${total}" )
}

// Closing progress bar window
Platform.runLater {
    progressUI.stage.close()
}


import java.util.concurrent.CountDownLatch
import javafx.animation.*
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.layout.*
import javafx.stage.*
import javafx.beans.binding.Bindings
import com.google.gson.GsonBuilder
import com.google.gson.Gson

import org.locationtech.jts.geom.*
import org.locationtech.jts.geom.util.PolygonalExtracter
import org.locationtech.jts.operation.union.UnaryUnionOp


// Comments for tool launcher icon and tooltip
// ICON: COPY
// Make new annotations at the intersection of others