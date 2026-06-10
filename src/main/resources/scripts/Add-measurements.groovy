/*
 * Author: Alec McKendell
 * Date: 2025.2.4
 * Description: Basic preferences popup with images to run code through
*/


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



// Project & preferences file
def project = getProject()
def currentImageData = getCurrentImageData()
def currentEntry = currentImageData ? project.getEntry( currentImageData ) : null
def projectDirectory = project.getBaseDirectory()

// Folder and file names of where preferences are stored in directory
def scriptPrefsFolderName = "Scripts_Preferences"
def scriptPrefsFileName   = "AddingMeasurements_Preferences" + ".json"

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
    
    
    // Making the basic popup
    def dialog = new Dialog()
    dialog.setTitle( "Adding measurements to annotations" )
    
    def root = new BorderPane()
    root.setPrefHeight( 500 )
    
    dialog.getDialogPane().buttonTypes.addAll( ButtonType.OK, ButtonType.CANCEL )
    def okBtn = dialog.getDialogPane().lookupButton( ButtonType.OK )
    okBtn.setText( "Add measurements" )
    
    
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
    
    
    
    // MIDDLE PANEL: Object pathclasses
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
    def pathClassBox = makeCheckboxBox( "Object classes", null, pathClassCBsMap, 200 )    
    pathClassBox.header.getChildren().addAll( pathClassBox.title, pathClassBox.spacer, pathClassBox.clear )    
    BorderPane.setMargin( pathClassBox.box, new Insets( 0, 15, 0, 0 ) )
    pathClassBox.CBs.each { it.setSelected( it.getUserData() in prePrefsMap.pathClasses ) }
    
    root.center = pathClassBox.box
    
    
    
    // RIGHT PANEL: Intensity and Morphology measurements
    
    // INTENSITY MEASUREMENTS
    // Getting comomon available channels across project images
    def addIntensityMeasurementsCB = new CheckBox( "Intensity measurements" )
    setTT( addIntensityMeasurementsCB, "Include intensity measurements" )
    addIntensityMeasurementsCB.setSelected( prePrefsMap.addIntensityMeasurements == true )
    
    def image1 = project.getImageList()[0]
    def image1Server = image1.readImageData().getServer()
    List image1Channels = image1Server.getMetadata().getChannels()
    List channelsMap = image1Channels.collect { [name: it.getName()] }
    def intensityChannelsBox = makeCheckboxBox( "Channels", null, channelsMap, 180, 250 )
    intensityChannelsBox.header.getChildren().addAll( intensityChannelsBox.title, intensityChannelsBox.spacer, intensityChannelsBox.clear ) 
    intensityChannelsBox.CBs.each { it.setSelected( it.getText() in prePrefsMap.intensityChannels ) }
    
    List intensityMeasurementsMap = ObjectMeasurements.Measurements.values().collect { [name: it.toString(), data: it] }
    def intensityMeasurementsBox = makeCheckboxBox( "Measurements",  "intensity measurements", intensityMeasurementsMap, 180, 200 )
    intensityMeasurementsBox.header.getChildren().addAll( intensityMeasurementsBox.selectAll )
    intensityMeasurementsBox.CBs.each { it.setSelected( it.getText() in prePrefsMap.intensityMeasurements ) }
    
    def intensityBoxes = new VBox( 10, intensityChannelsBox.box, intensityMeasurementsBox.box )
    def intensityPanel = new VBox( 10, addIntensityMeasurementsCB, intensityBoxes )
    
    intensityBoxes.disableProperty().bind(
        addIntensityMeasurementsCB.selectedProperty().not()
    )
    
    
    // MORPHOLOGY MEASUREMENTS
    def addMorphologyMeasurementsCB = new CheckBox( "Morphology measurements" )
    setTT( addMorphologyMeasurementsCB, "Include morphology measurements" )
    addMorphologyMeasurementsCB.setSelected( prePrefsMap.addMorphologyMeasurements == true )
    
    List morphologyMeasurementsMap = ObjectMeasurements.ShapeFeatures.values().collect { [name: it.toString(), data: it] }
    def morphologyMeasurementsBox = makeCheckboxBox( "Measurements", "morphology measurements", morphologyMeasurementsMap, 220 )
    morphologyMeasurementsBox.header.getChildren().addAll( morphologyMeasurementsBox.selectAll )
    morphologyMeasurementsBox.CBs.each { it.setSelected( it.getText() in prePrefsMap.morphologyMeasurementStrings ) }
    
    morphologyMeasurementsBox.box.disableProperty().bind(
        addMorphologyMeasurementsCB.selectedProperty().not()
    )
    
    def morphologyPanel = new VBox( 10, addMorphologyMeasurementsCB, morphologyMeasurementsBox.box )
    
    
    // Adding all together
    def allMeasurementsPanel = new HBox( 10, intensityPanel, morphologyPanel )
    root.right = allMeasurementsPanel
    
    
    
    // Disabling the "OK" button if appropriate inputs are not met
    def anySelectedImage = Bindings.createBooleanBinding(
        { imagesBox.CBs.any { it.isSelected() } },
        *imagesBox.CBs.collect { it.selectedProperty() }
    )
    def anySelectedChannel = Bindings.createBooleanBinding(
        { intensityChannelsBox.CBs.any { it.isSelected() } },
        *intensityChannelsBox.CBs.collect { it.selectedProperty() }
    )
    def anySelectedIntensityMeasurement = Bindings.createBooleanBinding(
        { intensityMeasurementsBox.CBs.any { it.isSelected() } },
        *intensityMeasurementsBox.CBs.collect { it.selectedProperty() }
    )
    def anySelectedMorphologyMeasurement = Bindings.createBooleanBinding(
        { morphologyMeasurementsBox.CBs.any { it.isSelected() } },
        *morphologyMeasurementsBox.CBs.collect { it.selectedProperty() }
    )
    
    okBtn.disableProperty().bind(
        anySelectedImage.not()
        .or( addIntensityMeasurementsCB.selectedProperty().not()
            .and( addMorphologyMeasurementsCB.selectedProperty().not() ) )
        .or( addIntensityMeasurementsCB.selectedProperty()
            .and( anySelectedChannel.not()
                .or ( anySelectedIntensityMeasurement.not() ) ) )
        .or( addMorphologyMeasurementsCB.selectedProperty()
            .and( anySelectedMorphologyMeasurement.not() ) )
    )
    
    
    // Attach root & show dialog
    dialog.getDialogPane().content = root
    
    def result = dialog.showAndWait()
    def button = result.get()
    
    if (button == ButtonType.OK) {                
        
        List<String>    selectedImages            = imagesBox.CBs.findAll { it.isSelected() }.collect { it.getText() }
        List<String>    selectedPathClasses       = pathClassBox.CBs.findAll { it.isSelected() }.collect { it.getUserData() }
        List            selectedChannels          = intensityChannelsBox.CBs.findAll { it.isSelected() }.collect { it.getText() }
        List            selectedIntensities       = intensityMeasurementsBox.CBs.findAll { it.isSelected() }.collect { it.getUserData() }
        ShapeFeatures[] selectedMorphologies      = morphologyMeasurementsBox.CBs.findAll { it.isSelected() }.collect { it.getUserData() }
        List<String>    selectedMorphologyStrings = morphologyMeasurementsBox.CBs.findAll { it.isSelected() }.collect { it.getText() }
        
        // Return all preferences of "OK" is selected
        return [
            selectedImages              : selectedImages,
            currentImage                : currentImageCB.isSelected(),
            pathClasses                 : selectedPathClasses,
            addIntensityMeasurements    : addIntensityMeasurementsCB.isSelected(),
            intensityChannels           : selectedChannels,
            intensityMeasurements       : selectedIntensities,
            addMorphologyMeasurements   : addMorphologyMeasurementsCB.isSelected(),
            morphologyMeasurements      : selectedMorphologies,
            morphologyMeasurementStrings: selectedMorphologyStrings
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
    updateProgressWindow( image: image.toString() )
    
    // If working on current image, modifying respective image data
    def imageData = image == currentEntry ? currentImageData : image.readImageData()
    
    def server = imageData.getServer()
    def metadata = server.getMetadata()
    def channels = metadata.getChannels()
    double pixelConversionFactor = metadata.getAveragedPixelSize()
    
    // Filter annotations of interest
    List annotations = imageData.getHierarchy().getAnnotationObjects().findAll {
        it.getPathClass()?.getName() in codePreferences.pathClasses
    }
    
    
    if (codePreferences.addIntensityMeasurements) {
        
        updateProgressWindow( stepUpdate: "Adding intensity measurements", stepFrac: "(0%)" )
        
        // Create server with only the desired channels to reduce processing time (otherwise will do all channels)
        def modifiedChannelServer = new TransformedServerBuilder( server )
            .extractChannels( *codePreferences.intensityChannels )
            .build()
        
        int numAnnos = annotations.size()
        
        annotations.eachWithIndex { anno, idx ->
            ObjectMeasurements.addIntensityMeasurements(
                    modifiedChannelServer,
                    anno,
                    pixelConversionFactor,
                    codePreferences.intensityMeasurements,
                    []
            )
            
            int percentDone = ( (idx+1) / numAnnos ) * 100
            updateProgressWindow( stepFrac: "(${percentDone}%)" )
        }
    }
    
    // Adding morphology measurements
    if (codePreferences.addMorphologyMeasurements) {
        
        updateProgressWindow( stepUpdate: "Adding morphology measurements", stepFrac: " " )
        
        def pixelCalibration = server.getPixelCalibration()
        ObjectMeasurements.addShapeMeasurements( annotations, pixelCalibration, codePreferences.morphologyMeasurements )
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

import qupath.lib.analysis.features.ObjectMeasurements
import qupath.lib.analysis.features.ObjectMeasurements.ShapeFeatures

import qupath.lib.images.servers.TransformedServerBuilder


// Comments for tool launcher icon and tooltip
// ICON: PLUS_CIRCLE
// Add intensity or morphology measurements to annotations