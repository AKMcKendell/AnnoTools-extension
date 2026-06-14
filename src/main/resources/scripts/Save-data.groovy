/*
 * Author: Alec McKendell
 * Date: 2026.6.10
 * Description: Exports annotation details based on selections with nesting levels included
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
def createProgressWindow( String title, int totalImages, boolean simplify = false ) {
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
        
        def stepLabel = new Label( "Step" )
        def stepNum = new Label()
        def stepUpdate = new Label()
        def stepProgress = new Label()
        def stepRow = new HBox( 5, stepLabel, stepNum, stepUpdate, stepProgress )
        
        def cancelBtn = new Button( "Cancel" )
        cancelBtn.setDisable( false )
        def spacer = new Region()
        HBox.setHgrow( spacer, Priority.ALWAYS )
        def timerLabel = new Label( "Elapsed: 00:00:00" )       
        def bottomRow = new HBox( 10, cancelBtn, spacer, timerLabel )
        bottomRow.setAlignment( Pos.CENTER_LEFT )
        
        List rows = [imageRow, barRow, stepRow, bottomRow]
        
        if (simplify) {
            rows.removeAll( imageRow, stepRow, bottomRow )
        }
        
        def vbox = new VBox( 10 )
        vbox.getChildren().addAll( rows )
        vbox.setPadding( new Insets( 5 ) )
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
            node.setMinWidth( Region.USE_PREF_SIZE )
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
            stepNum      : stepNum,
            stepUpdate   : stepUpdate,
            stepProgress : stepProgress,
            cancelFlag   : cancelFlag,
            timeline     : timeline
        ]
    }
}

// Function: Show update on code progress
def updateProgressWindow( Map args = [:] ) {
    String image      = args.image
    String stepNum    = args.stepNum
    String stepUpdate = args.stepUpdate
    String stepFrac   = args.stepFrac
    double imageFrac  = args.imageFrac ?: 0
    String imageStep  = args.imageStep

    Platform.runLater {
        if (image)      { args.UI.imageName.setText( image ) }
        if (stepNum)    { args.UI.stepNum.setText( stepNum ) }
        if (stepUpdate) { args.UI.stepUpdate.setText( stepUpdate ) }
        if (stepFrac)   { args.UI.stepProgress.setText( stepFrac ) }
        if (imageFrac)  { args.UI.bar.setProgress( imageFrac ) }
        if (imageStep)  { args.UI.progressLabel.setText( imageStep ) }
    }
}

// Function: Make a checkbox that selects/deselects all checkboxes
def makeSelectAllCB( List groupCBs ) {
    def selectAllCB = new CheckBox()
    
    // Select / deselect all action
    selectAllCB.setOnAction {
        selectAllCB.setIndeterminate( false )
        groupCBs.each { it.setSelected( it.isVisible() ? selectAllCB.isSelected() : it.isSelected() ) }
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
        checkboxBox.setMinWidth( prefWidth )
        checkboxBox.setMaxWidth( prefWidth )
    }
    if (prefHeight > 0) {
        checkboxBox.setPrefHeight( prefHeight )
        checkboxBox.setMaxHeight( prefHeight )
        checkboxBox.setMinHeight( prefHeight )
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
    
    String TTword = keyWord ?: title
    selectAllCB.setText( "${title.capitalize()} [0]" )
    setTT( selectAllCB, selectAllCB.isSelected() ? "Deselect all ${TTword.uncapitalize()}" : "Select all ${TTword.uncapitalize()}" )
    
    // Add listeners to checkboxes (for changing instructions/labels)
    checkboxes.each { cb ->
        cb.selectedProperty().addListener { obs, oldVal, newVal ->            
            
            def selectedNow = checkboxes.findAll { it.isSelected() && it.isVisible() }.collect { it.getText() }
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
        
        cb.visibleProperty().addListener { obs, oldVal, newVal ->
            cb.setSelected( !cb.isSelected() )
            cb.setSelected( !cb.isSelected() )
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

// Function: Collect ALL possible measurement names for each pathclass
def collectAllMeasurements( objects ) {
    def measurementsByClass = [:]
    objects.each { obj ->
        def pathClass = obj.getPathClass()
        def measurements = obj.getMeasurementList()
        measurements?.getMeasurementNames()?.each { name ->
            measurementsByClass.computeIfAbsent( pathClass?.getName(), { new LinkedHashSet() } ) << name
        }
    }
    return measurementsByClass
}

// Function: Collect ALL possible metadata names for each pathclass
def collectAllMetadata( objects ) {
    def mdByClass = [:]
    objects.each { obj ->
        def pathClass = obj.getPathClass()
        def md = obj.getMetadata().keySet()
        md?.each { name ->
            mdByClass.computeIfAbsent( pathClass?.getName(), { new LinkedHashSet() } ) << name
        }
    }
    return mdByClass
}

// Function: Scan the entire project to collect annotation field availability by class
def collectProjectAnnotationIndex( project ) {
    List projectImages = project.getImageList()
    
    def collectingUI = createProgressWindow( "Getting project specifics", projectImages.size(), true )
    int imagesScanned = 0
    
    def index = [
        classCounts        : [:].withDefault { 0 },
        measurementsByClass: [:].withDefault { new LinkedHashSet() },
        metadataByClass    : [:].withDefault { new LinkedHashSet() }
    ]
    
    projectImages.each { entry ->
        def imageData = null
        try {
            imageData = entry.readImageData()
            if (imageData) {
                def annos = imageData.getHierarchy()?.getAnnotationObjects() ?: []
                annos.each { obj ->
                    def className = obj.getPathClass()?.getName()
                    if (className) {
                        index.classCounts[ className ] = (index.classCounts[ className ] ?: 0) + 1
                        
                        obj.getMeasurementList()?.getMeasurementNames()?.each { name ->
                            index.measurementsByClass[ className ] << name
                        }
                        
                        obj.getMetadata()?.keySet()?.each { key ->
                            index.metadataByClass[ className ] << key
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Skip images that cannot be read for any reason
        } finally {
            try {
                imageData?.close()
            } catch (Exception ignored2) {
                // Ignore close failures
            }
        }
        
        // - Reporting progress -
        imagesScanned++
        double frac = imagesScanned / projectImages.size() 
        updateProgressWindow( UI: collectingUI, imageFrac: frac, imageStep: "${imagesScanned}/${projectImages.size()}" )
    }
    
    // Closing progress bar window
    Platform.runLater {
        collectingUI.stage.close()
    }
    
    index.measurementsByClass = index.measurementsByClass.collectEntries { k, v -> [k, new LinkedHashSet( v )] }
    index.metadataByClass = index.metadataByClass.collectEntries { k, v -> [k, new LinkedHashSet( v )] }
    index.classCounts = index.classCounts.collectEntries { k, v -> [k, v as int] }
    return index
}



// Project & preferences file
def currentImageData = getCurrentImageData()
def currentEntry = currentImageData ? project.getEntry( currentImageData ) : null
def projectDirectory = project.getBaseDirectory()

// Folder and file names of where preferences are stored in directory
def scriptPrefsFolderName = "Scripts_Preferences"
def scriptPrefsFileName   = "ExportingAnnoDetails_Preferences" + ".json"
def fieldCacheFileName    = "ExportingAnnoDetails_ProjectFieldCache" + ".json"

def prefsFolder = new File( projectDirectory, scriptPrefsFolderName )
if (!prefsFolder.exists()) { prefsFolder.mkdirs() }

def prefsFile = new File( prefsFolder, scriptPrefsFileName )
if (!prefsFile.exists()) { prefsFile.createNewFile() }

def fieldCacheFile = new File( prefsFolder, fieldCacheFileName )

// Read previous preferences
def prePrefsMap = [:]
def inputGson = new Gson()
def outputGson = new GsonBuilder().setPrettyPrinting().create()
if (prefsFile.length() > 0L && prefsFile.getText() != "null") {
    prePrefsMap = inputGson.fromJson( prefsFile.getText(), Map )
}

// Build map of project specifics to cache
def projectFingerprint = { proj ->
    def imageNames = proj.getImageList().collect { it.getImageName() }.sort()
    def baseDir = proj.getBaseDirectory()?.getAbsolutePath() ?: ""
    return [ baseDir: baseDir,
             images:  imageNames ]
}

// If chache with project fingerprint exists, gets measurement/metadata specifics from there
// If not, regenerates by scanning the project
def loadProjectCache = { proj, File cacheFile, boolean forceReload = false ->
    def currentFingerprint = projectFingerprint( proj )
    if (cacheFile.exists() && cacheFile.length() > 0L && !forceReload) {
        try {
            def cached = inputGson.fromJson( cacheFile.text, Map )
            if (cached?.fingerprint == currentFingerprint && cached?.index instanceof Map) {
                return cached
            }
        } catch (Exception ignored) {
            // rebuild below
        }
    }

    def builtIndex = collectProjectAnnotationIndex( proj )
    def timestamp = LocalDateTime.now().format(
        DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ss" )
    )

    def cacheObj = [
        timeStamp  : timestamp,
        fingerprint: currentFingerprint,
        index      : builtIndex
    ]

    cacheFile.text = outputGson.toJson(cacheObj)
    return cacheObj
}

def projectCache = loadProjectCache( project, fieldCacheFile )


// --- PREFERENCES POPUP ---
def codePreferences = runOnFxThreadAndWait {
    
    def dialog = new Dialog()
    dialog.setTitle( "What should be saved?" )
    
    def root = new BorderPane()
    root.setPrefHeight( 350 )
    
    dialog.getDialogPane().buttonTypes.addAll( ButtonType.OK, ButtonType.CANCEL )
    def okBtn = dialog.getDialogPane().lookupButton( ButtonType.OK )
    okBtn.setText( "Save" )
    
    
    
    // LEFT PANEL: Image Selection
    def imageNames = project.getImageList().collect { it.getImageName() }
    def imagesBox = makeCheckboxBox( "select images", "images", imageNames.collect { [name: it] } )
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
    
    
    
    // CENTER PANEL: PathClass selection list
    def countsByClass
    def pathClassBox
    
    def setPcBox = {
        def projectPathClasses = project.getPathClasses().findAll { it?.getName() } // No null (those with a name)
        countsByClass = projectCache.index.classCounts ?: [:]
        
        // Organizing any pathclasses with objects in the project to the top of the list
        def highCounts = []
        def lowCounts = []
        projectPathClasses.each { pc ->
            def entry = [name: pc.getName(), num: countsByClass[ pc.getName() ] ?: 0]
            (entry.num > 0 ? highCounts : lowCounts) << entry
        }
        highCounts.sort { -it.num } // Sort for descending abundance
        def pathClassNamesAndNum = highCounts + lowCounts
        List pathClassCBsMap = pathClassNamesAndNum.collect { [name: (it.num > 0 ? "${it.name} [${it.num as int}]" : it.name), data: it.name] }
        pathClassBox = makeCheckboxBox( "Project object classes", null, pathClassCBsMap, 220 )    
        pathClassBox.header.getChildren().addAll( pathClassBox.title, pathClassBox.spacer, pathClassBox.clear )    
        pathClassBox.CBs.each { it.setSelected( it.getUserData() in (prePrefsMap.objectClasses ?: []) ) }
        BorderPane.setMargin( pathClassBox.box, new Insets( 0, 15, 0, 0 ) )
        
        root.center = pathClassBox.box
    }
    
    setPcBox()
    
    
    
    
    
    // RIGHT PANEL:  
    def measurementsBox
    def metadataBox
    
    def setMmMdBox = {
        def measurementsByClass = projectCache.index.measurementsByClass ?: [:]
        LinkedHashSet allMeasurements = new LinkedHashSet( (measurementsByClass.values() ?: []).collectMany { it ?: [] } )
        def allMeasurementsMap = allMeasurements.collect { [name: it] }
        measurementsBox = makeCheckboxBox( "Measurements to include", "annotation measurements in project", allMeasurementsMap, 350 )
        measurementsBox.header.getChildren().setAll( measurementsBox.selectAll )
        measurementsBox.CBs.each { it.setSelected( it.getText() in (prePrefsMap.measurements ?: []) ) }
        measurementsBox.box.setMinWidth( Region.USE_PREF_SIZE )
        
        def metadataByClass = projectCache.index.metadataByClass ?: [:]
        LinkedHashSet allMetadata = new LinkedHashSet( (metadataByClass.values() ?: []).collectMany { it ?: [] } )
        def allMetadataMap = allMetadata.collect { [name: it] }
        metadataBox = makeCheckboxBox( "Metadata to include", "annotation metadata in project", allMetadataMap, 250 )
        metadataBox.header.getChildren().setAll( metadataBox.selectAll )
        metadataBox.CBs.each { it.setSelected( it.getText() in (prePrefsMap.metadata ?: []) ) }
        metadataBox.box.setMinWidth( Region.USE_PREF_SIZE )
        
        pathClassBox.CBs.each { cb ->
            cb.selectedProperty().addListener { obs, oldVal, newVal ->
                def selectedPCs = pathClassBox.CBs.findAll { it.isSelected() }.collect { it.getUserData() }
                
                LinkedHashSet availableMeasurements = new LinkedHashSet( selectedPCs.collectMany { measurementsByClass[ it ] ?: [] } )
                measurementsBox.CBs.each { cbCheck ->
                    boolean match = cbCheck.getText() in availableMeasurements
                    cbCheck.setVisible( match )
                    cbCheck.setManaged( match )
                }
                
                LinkedHashSet availableMetadata = new LinkedHashSet( selectedPCs.collectMany { metadataByClass[ it ] ?: [] } )
                metadataBox.CBs.each { cbCheck ->
                    boolean match = cbCheck.getText() in availableMetadata
                    cbCheck.setVisible( match )
                    cbCheck.setManaged( match )
                }
            }
            cb.setSelected( !cb.isSelected() )
            cb.setSelected( !cb.isSelected() )
        }
    }
    
    setMmMdBox()
    
    def MM_MD_Panel = new HBox( 10, measurementsBox.box, metadataBox.box )
    
    def timeStamp = new Label( "Last scan: ${projectCache.timeStamp}" )
    def rightSpacer = new Region()
    HBox.setHgrow( rightSpacer, Priority.ALWAYS )
    def refreshBtn = new Button( "Refresh" )
    def topRow = new HBox( 10, timeStamp, rightSpacer, refreshBtn )
    
    refreshBtn.setOnAction { e ->
        refreshBtn.setDisable( true )
    
        Thread.startDaemon {
            def newCache = loadProjectCache( project, fieldCacheFile, true ) // rebuild
    
            Platform.runLater {
                projectCache = newCache
                setPcBox()
                setMmMdBox()
                timeStamp.setText( "Last scan: ${projectCache.timeStamp}" )
                refreshBtn.setDisable( false )
            }
        }
    
        e.consume()
    }
    
    def rightPanel = new VBox( 10, topRow, MM_MD_Panel)
    
    root.right = rightPanel
    
    
    
    // BOTTOM ROW: Open file saver browser
    boolean save = false
    def filePath
    def folderPath
    okBtn.addEventFilter( ActionEvent.ACTION ) { e ->
        def chooser = new FileChooser()
        chooser.setTitle( "Choose save location" )
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter( "CSV files", "*.csv" )
        )
        
        def startingDir = prePrefsMap.folder ?
            new File( prePrefsMap.folder ) :
            project.getBaseDirectory()
    
        chooser.setInitialFileName( "results.csv" )
        chooser.setInitialDirectory( startingDir )
        
        def owner  = dialog.getDialogPane().getScene()?.getWindow()
        def chosen = chooser.showSaveDialog( owner )
        
    
        if (chosen == null) {
            e.consume()
            return
        }
        
        filePath = chosen.getAbsolutePath()
        if (!filePath.toLowerCase().endsWith( ".csv" )) {
            filePath += ".csv"
        }
        folderPath = chosen.getParent()
        
        save = true
    }
    
    
    
    // Disabling the "OK" button if not all inputs are met    
    def anySelectedImage = Bindings.createBooleanBinding(
        { imagesBox.CBs.any { it.isSelected() } },
        *imagesBox.CBs.collect { it.selectedProperty() }
    )
    
    okBtn.disableProperty().bind(
        anySelectedImage.not()
    )
    
    
    // Attach root & show dialog
    dialog.getDialogPane().content = root   
    dialog.showAndWait()
    
    if (save) {
        def selectedImages = imagesBox.CBs
                .findAll { it.isSelected() }
                .collect { it.getText() }
        def selectedPathClasses = pathClassBox.CBs
                .findAll { it.isSelected() }
                .collect { it.getUserData() }
        def selectedMeasurements = measurementsBox.CBs
                .findAll { it.isSelected() && it.isVisible() }
                .collect { it.getText() }
        def selectedMetadata = metadataBox.CBs
                .findAll { it.isSelected() && it.isVisible() }
                .collect { it.getText() }
        
        // Return all preferences of "OK" is selected
        return [
            selectedImages : selectedImages,
            currentImage   : currentImageCB.isSelected(),
            objectClasses  : selectedPathClasses,
            measurements   : selectedMeasurements,
            metadata       : selectedMetadata,
            file           : filePath,
            folder         : folderPath
        ]  
    }
}


// Exit if cancelling
if (codePreferences == null) {
    return
    
} else { // Write to .json if exporting/importing
    
    prefsFile.text = outputGson.toJson( codePreferences )
}



def imagesToRun = project.getImageList().findAll { it.getImageName() in codePreferences.selectedImages }

def total = imagesToRun.size()
def progressUI = createProgressWindow( "Processing", total )
def cancelFlag = progressUI.cancelFlag
int processed = 0

// Making a matrix for all images
List completeMatrixMap = []
List headerRow = []
List emptyImages = []


imageLoop:
for (image in imagesToRun) {
    
    if (cancelFlag.get()) break
    
    // Reporting current image
    updateProgressWindow( UI: progressUI, image: image.toString() )
    
    
    // If working on current image, modifying respective image data
    def imageData = image == currentEntry ? currentImageData : image.readImageData()
    def hierarchy = imageData.getHierarchy()
    def annotations = hierarchy.getAnnotationObjects().findAll { it.getPathClass()?.getName() in codePreferences.objectClasses }
    def pixelCalibration = imageData.getServer().getMetadata().getPixelCalibration()    
    String imageName = image.getImageName()
    
    
    // Warning and skipping image if no applicable annotations
    if (annotations.isEmpty()) {
        emptyImages << imageName
        continue
    }
    
    
    // Build rows: one row per annotation
    annotations.each { obj ->
        Map rowMap = [:]
        
        rowMap[ "Image_Name" ] = imageName
        rowMap[ "ID" ]         = obj.getID()
        rowMap[ "Parent_ID" ]  = obj.getParent() ? obj.getParent().getID() : null
        rowMap[ "Level" ]      = obj.getLevel()
        rowMap[ "Name" ]       = obj.getName()
        rowMap[ "Class" ]      = obj.getPathClass()?.getName()
        rowMap[ "Area" ]       = obj.getROI()?.getScaledArea( pixelCalibration )
        
        def measurements = obj.getMeasurementList()
        codePreferences.measurements.each { mmName ->
            rowMap[ mmName ] = measurements.get( mmName ) ?: null
        }
        
        def metadata = obj.getMetadata()
        codePreferences.metadata.each { mdKey ->
            rowMap[ mdKey ] = metadata.get( mdKey ) ?: null
        }
        
        headerRow = (headerRow + rowMap.keySet()).unique()
        completeMatrixMap << rowMap
    }
    
    
    // Closing background imagedata for non-current image
    if (image != currentEntry) {
        imageData.close()
    }
    
    
    // - Reporting progress -
    processed++
    double frac = processed / total 
    updateProgressWindow( UI: progressUI, imageFrac: frac, imageStep: "${processed}/${total}" )
}


// --- Final output matrix processing ---
// Remove columns where every row has null for that key
headerRow = headerRow.findAll { key ->
    completeMatrixMap.any { rowMap -> rowMap[ key ] != null }
}

// Then add the current image matrix rows
List outputMatrix = completeMatrixMap.collect { rowMap ->
    headerRow.collect { key -> rowMap[ key ] }
}

// Add header
outputMatrix.addFirst( headerRow )


// Closing progress bar window
Platform.runLater {
    progressUI.stage.close()
}


// Firing conditional warnings if needed
boolean export = true
if (emptyImages.size() == imagesToRun.size()) {
    Dialogs.showWarningNotification( "WARNING: No data exported", "No images contain viable annotations." )
    export = false
} else if (!emptyImages.isEmpty()) {
    Dialogs.showWarningNotification( "WARNING: Image(s) not included (no viable annotations)", " - " + emptyImages.join( "\n - ") )
}


// Exporting all data if allowed
if (export) {
    def file = new File( codePreferences.file )
    
    // Creating a CSV file if one doesn't exist (to append to)
    if (!file.exists()) {  
        file.createNewFile() 
    }
    
    // Catching any errors if the file is open
    exportAttemptLoop:
    while (true) {
        try {
            file.withWriter { writer ->
                outputMatrix.each {
                    def row = it.join( "," )
                    writer.writeLine( row )
                }
            }
            
            Dialogs.showInfoNotification( "Saved successfully!", file.toString() )
            
            break exportAttemptLoop
        
        } catch (Exception e) {
            
            boolean retry = runOnFxThreadAndWait {
                def fileOpenAlert = new Alert( Alert.AlertType.CONFIRMATION )
                
                fileOpenAlert.setTitle( "Cannot write!" )
                fileOpenAlert.setHeaderText( "\"${file.getName()}\" is open in another application..." )
                fileOpenAlert.setContentText( "Close the file and try again." )
                
                def cancelBtn   = new ButtonType( "Cancel", ButtonBar.ButtonData.CANCEL_CLOSE )
                def tryAgainBtn = new ButtonType( "Try again", ButtonBar.ButtonData.YES )
                fileOpenAlert.getDialogPane().buttonTypes.setAll( cancelBtn, tryAgainBtn )
                
                def result = fileOpenAlert.showAndWait().orElse( cancelBtn )
                return result == tryAgainBtn
            }
            
            if (!retry) { break exportAttemptLoop }
        }
    }
}



import java.util.concurrent.CountDownLatch
import javafx.animation.*
import javafx.application.Platform
import javafx.event.ActionEvent
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


// Comments for tool launcher icon and tooltip
// ICON: SAVE
// Custom export for annotation information