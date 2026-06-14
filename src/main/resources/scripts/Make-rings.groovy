/*
 * Author: Alec McKendell
 * Date: 2026.3.29
 * Description: Creates concentric rings around annotations of interest.
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
                Tooltip.install(obj, newTT)
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

// Function: Take a list of annotations and return a list of corresponding geometries with filled holes
def fillAnnoHoles( List annoGeoms ) {    
    
    def filledGeomsList = []
    
    annoGeoms.each { annoGeom ->       
        def geomFactory = annoGeom.getFactory()    
        Geometry filledGeom
    
        if (annoGeom instanceof Polygon) {
            filledGeom = geomFactory.createPolygon( annoGeom.getExteriorRing() )
        } else if (annoGeom instanceof MultiPolygon) {
            def polys = []
            for (i in 1..annoGeom.getNumGeometries()) {
                def p = annoGeom.getGeometryN( i-1 )
                polys << geomFactory.createPolygon( p.getExteriorRing() )
            }
            filledGeom = geomFactory.createMultiPolygon( polys as Polygon[] )
        } else {
            filledGeom = annoGeom // fallback
        }    
        filledGeomsList << filledGeom
    }    
    return filledGeomsList
}

// Function: Recursively extract polygonal parts from mixed geometries
// This is because modifying geometries using built in functions can fail if either shape is a GeometryCollection
def getPolygonParts( Geometry g_parts ) {
    if (g_parts instanceof Polygonal)
        return [g_parts]

    if (g_parts instanceof GeometryCollection) {
        def parts = []
        for (i in 1 .. g_parts.getNumGeometries()) {
            parts.addAll( getPolygonParts( g_parts.getGeometryN( i-1 ) ) )
        }
        return parts
    }

    return []
}

// Function: Make a geometry into a flattened polygon for easier manipulation without erroring
def toPolygonGeometry( Geometry g_toPoly ) {
    def parts = getPolygonParts( g_toPoly )
    if (parts.isEmpty()) { return g_toPoly.getFactory().createPolygon() }
    return parts.size() == 1 ? parts[0] : UnaryUnionOp.union( parts )
}

// Function: Final safety wrapper for ROI conversion; uses buffer(0) only if the cleaned geometry is invalid
def cleanForROI( Geometry g_clean ) {
    if (g_clean == null || g_clean.isEmpty()) {
        return g_clean?.getFactory()?.createPolygon()
    }

    def cleaned = toPolygonGeometry( g_clean )

    if (cleaned == null || cleaned.isEmpty()) {
        return g_clean.getFactory().createPolygon()
    }

    if (!cleaned.isValid()) {
        cleaned = cleaned.buffer( 0 ) // fallback only
    }

    return cleaned
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
def scriptPrefsFileName   = "MakingRings_Preferences" + ".json"

def prefsFolder = new File( projectDirectory, scriptPrefsFolderName )
if (!prefsFolder.exists()) { prefsFolder.mkdirs() }

def prefsFile = new File( prefsFolder, scriptPrefsFileName )
if (!prefsFile.exists()) { prefsFile.createNewFile() }

// Read previous preferences
def prePrefsMap = [:]
if (prefsFile.length() > 0L && prefsFile.getText() != "null") {
    
    def inputGson = new Gson()
    prePrefsMap = inputGson.fromJson( prefsFile.text, Map )
}


// --- PREFERENCES POPUP ---
def codePreferences = runOnFxThreadAndWait {
    
    def dialog = new Dialog()
    dialog.setTitle( "Making rings around annotations" )
    
    def root = new BorderPane()
    root.setPrefHeight( 350 )
    
    dialog.getDialogPane().buttonTypes.addAll( ButtonType.OK, ButtonType.CANCEL )
    def okBtn = dialog.getDialogPane().lookupButton( ButtonType.OK )
    okBtn.setText( "Make rings" )
    
    
    
    // TOP ROW: Expansion distances
    int maxExpansions = 5
    
    // Parse comma-separated integers
    def parseIntegerList = { String text ->    
        if (!text?.trim())
            return []     
        def values = []    
        text.split( "," ).each { part ->
            def trimmed = part.trim()
            values << trimmed.toInteger()
        }
        return values
    }
    
    def expansionLabel = new Label( "Expansion radii (μm):" )
    def expansionField = new TextField()
    expansionField.setPromptText( "e.g. \"6,16,31\"" )
    setTT( [expansionLabel, expansionField], "Radii of expansion rings using numbers and commas only (max ${maxExpansions})." )
    
    def previewLabel = new Label( "=" )  
    def expansionsPreview = new Label( "[]" )
    setTT( [previewLabel, expansionsPreview], "Preview of final expansions.")
    
    List<Integer> expansions = []
    
    expansionField.textProperty().addListener { obs, oldVal, newVal ->
        
        // Allow only digits and commas (no double commas)
        if (!newVal.matches( "[\\d,]*" ) || newVal.contains( ",," )) {
            expansionField.setText( oldVal )
            return
        }
        
        // Capped at 5 expansions
        List<Integer> newExpansions = parseIntegerList( newVal ).unique().sort()
        if (newExpansions.size() >= maxExpansions && newVal.endsWith( "," ) || newVal.startsWith( "," )) {
            expansionField.setText( oldVal )
            return
        }
        
        expansions = newExpansions
        expansionsPreview.setText( expansions.toString() )
    }
    
    // Setting the expansions to previous values
    boolean correctCriteria = prePrefsMap.expansionsMicrons instanceof List &&
        prePrefsMap.expansionsMicrons.every { it instanceof Number } &&
        prePrefsMap.expansionsMicrons.size() <= maxExpansions
    expansionField.setText( correctCriteria ? 
        prePrefsMap.expansionsMicrons.collect { it.intValue().toString() }.join( "," ) :
        ""
    )
    
    def expansionRow = new HBox( 8 )
    expansionRow.setPadding( new Insets( 10 ) )
    expansionRow.setAlignment( Pos.CENTER_LEFT )
    expansionRow.getChildren().addAll(
        expansionLabel,
        expansionField,
        previewLabel,
        expansionsPreview
    )
    
    // Add it to your existing layout
    root.top = expansionRow
    
    
    
    // CENTER PANEL: Image Selection
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
    
    root.center = imagesBox.box
    
    
    
    // RIGHT PANEL: PathClass selection list
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
    pathClassBox.CBs.each { it.setSelected( it.getUserData() in prePrefsMap.objectClasses ) }
    
    root.right = pathClassBox.box
    
    
    
    // BOTTOM ROW:
    def expandOnlyOutwardCheck = new CheckBox( "Only expand outward" )
    setTT( expandOnlyOutwardCheck, "Expand only outward rather than also inward if central object has holes?")
    expandOnlyOutwardCheck.setSelected( prePrefsMap.expandOnlyOutward instanceof Boolean ? prePrefsMap.expandOnlyOutward : true )
    
    def contstrainToParentCheck = new CheckBox( "Constrain to parent" )
    setTT( contstrainToParentCheck, "Constrain expansions to the parent object of central object?")
    contstrainToParentCheck.setSelected( prePrefsMap.constrainToParent instanceof Boolean ? prePrefsMap.constrainToParent : true )
    
    // Creating row and adding children
    def secondRow = new HBox( 10 )
    secondRow.setPadding( new Insets( 10 ) )
    secondRow.getChildren().addAll(
        expandOnlyOutwardCheck,
        contstrainToParentCheck
    )
    root.bottom = secondRow
    
    
    
    // Disabling the "OK" button if not all inputs are met    
    def anySelectedImage = Bindings.createBooleanBinding(
        { imagesBox.CBs.any { it.isSelected() } },
        *imagesBox.CBs.collect { it.selectedProperty() }
    ) 
    def anySelectedPathClass = Bindings.createBooleanBinding(
        { pathClassBox.CBs.any { it.isSelected() } },
        *pathClassBox.CBs.collect { it.selectedProperty() }
    )  
    def hasExpansions = Bindings.createBooleanBinding(
        { !parseIntegerList( expansionField.getText() ).isEmpty() },
        expansionField.textProperty()
    )
    
    okBtn.disableProperty().bind(
        anySelectedImage.not()
            .or( anySelectedPathClass.not() )
            .or( hasExpansions.not() )
    )
    
    
    // Attach root & show dialog
    dialog.getDialogPane().content = root
    
    def result = dialog.showAndWait()
    def button = result.get()
    
    if (button == ButtonType.OK) {                
        def selectedImages = imagesBox.CBs
                .findAll { it.isSelected() }
                .collect { it.getText() }
        def selectedPathClasses = pathClassBox.CBs
                .findAll { it.isSelected() }
                .collect { it.getUserData() }
        
        // Return all preferences of "OK" is selected
        return [
            selectedImages    : selectedImages,
            currentImage      : currentImageCB.isSelected(),
            objectClasses     : selectedPathClasses,
            expansionsMicrons : expansions,
            expandOnlyOutward : expandOnlyOutwardCheck.isSelected(),
            constrainToParent : contstrainToParentCheck.isSelected()
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



def imagesToRun = project.getImageList().findAll { it.getImageName() in codePreferences?.selectedImages }

def total = imagesToRun.size()
def progressUI = createProgressWindow( "Processing", total )
def cancelFlag = progressUI.cancelFlag
int processed = 0

// Closure to show update on code progress
def updateProgressWindow = { Map args = [:] ->
    String image      = args.image
    String stepNum    = args.stepNum
    String stepUpdate = args.stepUpdate
    String stepFrac   = args.stepFrac
    double imageFrac  = args.imageFrac ?: 0
    String imageStep  = args.imageStep

    Platform.runLater {
        if (image)      { progressUI.imageName.setText( image ) }
        if (stepNum)    { progressUI.stepNum.setText( stepNum ) }
        if (stepUpdate) { progressUI.stepUpdate.setText( stepUpdate ) }
        if (stepFrac)   { progressUI.stepProgress.setText( stepFrac ) }
        if (imageFrac)  { progressUI.bar.setProgress( imageFrac ) }
        if (imageStep)  { progressUI.progressLabel.setText( imageStep ) }
    }
}

imageLoop:
for (image in imagesToRun) {
    
    if (cancelFlag.get()) {
        break
    }
    
    // Reporting current image
    updateProgressWindow( image: image.toString() )
    
    // Getting image-specific information
    // If working on current image, modifying respective image data
    def imageData = image == currentEntry ? currentImageData : image.readImageData()
    def cal = imageData.getServer().getPixelCalibration()
    double pixelSize = cal.getAveragedPixelSizeMicrons()
    
    def hierarchy = imageData.getHierarchy()
    
    int netSteps = (codePreferences.objectClasses.size() * (codePreferences.expansionsMicrons.size() + 1))
    int step = 1
    
    
    pathclassLoop:
    for (objectClass in codePreferences.objectClasses) {
        
        // Get central objects
        def baseObjs = hierarchy.getAnnotationObjects().findAll {
            it.getPathClass()?.getName() == objectClass && 
            it.getROI()?.getArea() > 0
        }
        baseObjs.each { it.setLocked( true ) }
        
        // Get expansion class names
        def expansionClasses = codePreferences.expansionsMicrons.collect { exp ->
            def expPathClassName = objectClass + "_" + exp
            return expPathClassName
        }
        
        // Clear previous expansions
        def objectsToRemove = hierarchy.getAnnotationObjects().findAll { 
            def pathClassName = it.getPathClass()?.getName()
            pathClassName in expansionClasses ||
            ( pathClassName.contains( objectClass + "_" ) && it.getParent() in baseObjs )
        }
        hierarchy.removeObjects( objectsToRemove, false )
        
        // Making sure new class(es) are in project classes
        def availableClasses = getQuPath().getAvailablePathClasses()
        def missingClasses = expansionClasses
                .collect { getPathClass( it ) }
                .findAll { it !in availableClasses }
        availableClasses.addAll( missingClasses )
        
        
        
        // --- RING GENERATION ---
        // Getting map with central object and relevant expansion geometries
        updateProgressWindow( stepNum: "${step}/${netSteps}:", stepUpdate: "Generating expansion map for ${objectClass}", stepFrac: "(0%)" )
        
        def expansionsMap = []
        
        baseObjs.eachWithIndex { annoCentral, idx ->
            // Normalize to Polygon/MultiPolygon before buffering.
            // Mask objects stored as GeometryCollections can produce unexpectedly wide
            // expansions if passed to buffer() directly.
            def geomCentral = toPolygonGeometry( annoCentral.getROI().getGeometry() )
            
            // Fill holes BEFORE buffering when expanding outward only.
            // buffer() on a holey geometry also shrinks inner rings inward, making the
            // resulting expansion appear wider than intended. Filling holes first ensures
            // the buffer only travels outward from the exterior boundary.
            if (codePreferences.expandOnlyOutward) {
                def filled = fillAnnoHoles( [geomCentral] )
                geomCentral = toPolygonGeometry( filled[0] )
            }
            
            def geomsAll = [geomCentral]
            
            codePreferences.expansionsMicrons.each { exp ->
                double expandPixels = exp / pixelSize            
                def geomExpanded = toPolygonGeometry( geomCentral.buffer( expandPixels ) )
                geomsAll << geomExpanded
            }
            expansionsMap << [centerObj: annoCentral, allGeoms: geomsAll]
            
            int pDone = ((idx+1) / baseObjs.size()) * 100
            updateProgressWindow( stepFrac: "(${pDone}%)" )
        }
        
        // Prep each group: geometries are already normalized by above so no re-cleaning needed.
        // Parent geometry is cached here once so it isn't recomputed on every ring level below.
        expansionsMap.each { objGroup ->
            objGroup.cleanedAllGeoms = objGroup.allGeoms
            objGroup.ringObjs = []
            if (codePreferences.constrainToParent) {
                def parent = objGroup.centerObj.getParent()
                objGroup.parentGeom = (parent?.getROI() != null) ? cleanForROI( parent.getROI().getGeometry() ) : null
            }
        }
        
        // Creating rings and nesting under central object
        expansionClasses.eachWithIndex { expClass, level ->
            
            // Reporting current step
            step ++
            updateProgressWindow( stepNum: "${step}/${netSteps}:", stepUpdate: "Working on ${expClass}", stepFrac: " " )
            
            // Union all objects' geometries at the inner level into a single mask to subtract.
            // This ensures each ring does not overlap any lower-level zone from any object —
            // including neighboring objects whose inner zones may intrude into this ring's area.
            // Geometries are already Polygon/MultiPolygon so no part-flattening is needed before union.
            def innerGeomsAtLevel = expansionsMap.collect { it.cleanedAllGeoms[ level ] }
            def combinedInnerMask = cleanForROI( UnaryUnionOp.union( innerGeomsAtLevel ) )
            
            // Getting each outer level geometry and subtracting the combined inner mask to form rings
            expansionsMap.each { objGroup ->
                def centerObj = objGroup.centerObj
                
                // cleanedAllGeoms entries are already normalized Polygon/MultiPolygon from above
                def ringGeom = objGroup.cleanedAllGeoms[ level + 1 ]
                
                if (!combinedInnerMask.isEmpty() && ringGeom.intersects( combinedInnerMask )) {
                    ringGeom = ringGeom.difference( combinedInnerMask )
                    ringGeom = cleanForROI( ringGeom )
                }
                
                // Clip to central object's parent if wanted; parentGeom cached in prep loop
                if (codePreferences.constrainToParent) {
                    def centralObjParentGeom = objGroup.parentGeom
                    if (centralObjParentGeom != null && !ringGeom.isEmpty() && ringGeom.getArea() > 0
                            && !centralObjParentGeom.covers( ringGeom )) {
                        ringGeom = ringGeom.intersection( centralObjParentGeom )
                        ringGeom = cleanForROI( ringGeom )
                    }
                }
                
                // Make the final objects as children of the central object
                if (!ringGeom.isEmpty() && ringGeom.getArea() > 0) {
                    def ringROI = GeometryTools.geometryToROI( ringGeom, centerObj.getROI().getImagePlane() )
                    def ringObj = PathObjects.createAnnotationObject( ringROI, getPathClass( expClass ) )
                    
                    hierarchy.addObjectBelowParent( centerObj, ringObj, false )
                    objGroup.ringObjs << ringObj
                    ringObj.setLocked( true )
                }
            }
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
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.simplify.TopologyPreservingSimplifier

import javafx.scene.control.TextFormatter

import java.util.function.UnaryOperator


// Comments for tool launcher icon and tooltip
// ICON: EXPAND
// Create concentric rings around annotations