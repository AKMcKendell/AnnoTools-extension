/*
 * Author: Alec McKendell
 * Date: 2025.2.4
 * Description: Exports/imports annotation objects with stored parent info for re-nesting.
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
            stepUpdate   : stepUpdate,
            stepProgress : stepProgress,
            cancelFlag   : cancelFlag,
            timeline     : timeline
        ]
    }
}

// Function: Resolve hierarchy
def resolveHierarchy( objects, hierarchy_resolve, image_resolve ) {
    // Build lookup maps from the full working set
    def byId = objects.collectEntries { [(it.getID().toString()): it] }
    def byName = objects.groupBy { it.getName() }
    
    def findParent = { obj ->
        def md = obj.getMetadata()
        def parent = null
        
        def parentID = md.get( "Parent_ID" )
        if (parentID) {
            parent = byId[ parentID ]
        }
        
        if (!parent) {
            def parentName = md.get( "Parent_Name" )
            if (parentName) {
                parent = byName[ parentName ]?.find()
            }
        }
        
        return parent
    }
    
    // Resolve in dependency order so parents are handled before children
    def remaining = new LinkedHashSet( objects )
    
    // List of any annotations that should have parents that the parent wasn't found
    def missingParents = []
    
    while (!remaining.isEmpty()) {
        def remainingIds = remaining.collect { it.getID().toString() } as Set
        
        def ready = remaining.findAll { obj ->
            def parentID = obj.getMetadata()?.get( "Parent_ID" )
            !parentID || !(parentID in remainingIds)
        }
        
        if (ready.isEmpty()) {
            ready = [remaining.first()]   // safety for bad metadata / cycles
        }
        
        ready.each { obj ->
            def md = obj.getMetadata()
            def parent = findParent( obj )
            
            def expectedParentID = md?.get( "Parent_ID" )
            def expectedParentName = md?.get( "Parent_Name" )
            
            if (parent) {
                hierarchy_resolve.addObjectBelowParent( parent, obj, false )
            }
            
            // Collect missing parent info (but don't stop them from adding)
            if (!parent && (expectedParentID || expectedParentName)) {
                missingParents << [
                    name: obj.getName(),
                    id: obj.getID(),
                    parentID: expectedParentID,
                    parentName: expectedParentName
                ]
            }
            
            remaining.remove( obj )
        }
    }
    
    // If any objects' parent were not found and thus not correctly nested, send warning to logger
    if (!missingParents.isEmpty()) {
        
        String imageName = image_resolve.getImageName()
    
        def loggerWarnMessage = new StringBuilder()
        loggerWarnMessage << "Missing parents: [${imageName}] (${missingParents.size()} objects)\n\n"
    
        missingParents.each {
            loggerWarnMessage << " - Object: [Name = ${it.name}, ID = ${it.id}] " +
                       " Parent: [Name = ${it.parentName}, ID = ${it.parentID}]\n"
        }
    
        logger.warn( loggerWarnMessage.toString() )
    
        Dialogs.showWarningNotification(
            "Hierarchy warning for ${imageName}",
            "${missingParents.size()} objects could not be nested.\nCheck the log for details."
        )
    }
}

// Function: Create alert selection window for user
def showAlert( Map args ) {
    return runOnFxThreadAndWait {
        def alert = new Alert( args.type ?: Alert.AlertType.CONFIRMATION )
        
        alert.setTitle( args.title ?: "" )
        alert.setHeaderText( args.header ?: null )
        alert.setContentText( args.content ?: "" )
        
        if (args.buttons) {
            alert.getDialogPane().buttonTypes.setAll( args.buttons )
        }
        
        // Set default button if provided
        if (args.defaultBtn) {
            def node = alert.getDialogPane().lookupButton( args.defaultBtn )
            node?.setDefaultButton( true )
        }
        
        return alert.showAndWait().orElse( args.fallback ?: args.buttons?.find { true } )
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
def scriptPrefsFileName   = "MovingAnnos_Preferences" + ".json"

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
    dialog.setTitle( "Moving annotations with parents" )
    
    def root = new BorderPane()
    root.setPrefSize( 700, 350 )
    
    
    // Defining mode buttons early because other functions rely on their status
    def modeLabel    = new Label( "Mode:" )
    def exportButton = new RadioButton( "Export" )
    def importButton = new RadioButton( "Import" )
    
    def modeButtons = new ToggleGroup()
    exportButton.setToggleGroup( modeButtons )
    importButton.setToggleGroup( modeButtons )
    
    // Restore previous mode or default to Export
    prePrefsMap.mode == "import" ? modeButtons.selectToggle( importButton ) : modeButtons.selectToggle( exportButton )
    
    
    // BOTTOM: Ok, cancel, and resolve hierarchy buttons
    def resolveHierarchyBtnType = new ButtonType( "Nest existing objects", ButtonBar.ButtonData.LEFT )
    
    dialog.getDialogPane().buttonTypes.addAll( ButtonType.OK, ButtonType.CANCEL, resolveHierarchyBtnType )
    def okBtn = dialog.getDialogPane().lookupButton( ButtonType.OK )
    def resolveHierarchyBtn = dialog.getDialogPane().lookupButton( resolveHierarchyBtnType )
    
    setTT( resolveHierarchyBtn, "Fix hierarchy of existing objects (no importing/exporting)" )
    ButtonBar.setButtonUniformSize( resolveHierarchyBtn, false )
    
    
    // TOP: Folder chooser + Export/Import mode + Include Parents
    def folderRow = new HBox( 10 )
    def folderTitleLabel = new Label( "Folder:" )
    
    // Closure: Get width of text in popup
    def getTextWidth = { String text_width, Font font_width ->
        def temp = new Text( text_width )
        temp.setFont( font_width )
        return temp.getLayoutBounds().getWidth()
    }
    
    def folderTitleWidth = getTextWidth( folderTitleLabel.getText(), folderTitleLabel.getFont() )
    folderTitleLabel.setPrefWidth( folderTitleWidth * 1.1 )
    folderTitleLabel.setMinWidth( folderTitleWidth * 1.1 )
    folderTitleLabel.setMaxWidth( folderTitleWidth * 1.1 )
    
    // Closure: Make the selected folder path viewable
    def formatFolderPathView = { String path_format, double maxWidth, Font font_format ->
        if (!path_format) return "No folder selected"
        def reversePathSplit = path_format.tokenize( "/\\\\" ).reverse()
        def viewPath = reversePathSplit[ 0 ]
        for (folder in reversePathSplit.drop( 1 )) {
            def extendedViewPath = folder + "  ▶  " + viewPath
            
            if (getTextWidth( extendedViewPath, font_format ) > (maxWidth * 0.85)) {
                return "...  ▶  " + viewPath
            }
            viewPath = extendedViewPath
        }
        return viewPath
    }
    
    def folderPathLabel = new Label()
    folderPathLabel.setMaxWidth( Double.MAX_VALUE )
    HBox.setHgrow( folderPathLabel, Priority.ALWAYS )
    
    // Formatting/updating folder path label
    boolean widthUpdated = false
    double folderPathLabelWidth = 0
    def folderPathLabelFont = folderPathLabel.getFont()
    folderPathLabel.widthProperty().addListener { obs, oldVal, newVal ->
        if (!widthUpdated) {
            folderPathLabelWidth = folderPathLabel.getWidth()
            widthUpdated = true
            
            folderPathLabel.setText( formatFolderPathView( prePrefsMap.selectedFolder ?: null, folderPathLabelWidth, folderPathLabelFont ) )
            setTT( folderPathLabel, prePrefsMap.selectedFolder ?: "No folder selected" )
            folderPathLabel.setStyle( prePrefsMap.selectedFolder ? "-fx-opacity: 0.8;" : "-fx-opacity: 0.5;" )
        }
    }
    
    def folderStatusRow   = new HBox( 10 )
    folderStatusRow.setPadding( new Insets( 0, 0, 15, 0 ) ) // small bottom gap before mode
    def folderStatusTitle = new Label( "Folder check:" )
    def folderStatusLabel = new Label( "" )
    folderStatusRow.getChildren().addAll( folderStatusTitle, folderStatusLabel )
    folderStatusLabel.setStyle( "-fx-opacity: 0.8;" )
    
    // Closure: Count number of geoJson files in a folder path
    def countGeoJsonFiles = { String folderPath ->
        if (!folderPath) return 0
        def folder = new File( folderPath )
        if (!folder.exists() || !folder.isDirectory()) return 0
        return folder.listFiles()?.count { f ->
            f.isFile() && f.name.toLowerCase().endsWith( ".geojson" )
        } ?: 0
    }
    
    // Closure: Change folder status label formatting
    def updateFolderStatus = { String folderPath ->
        if (!folderPath) {
            folderStatusLabel.setText( "No folder selected" )
            folderStatusLabel.setStyle( "-fx-opacity: 0.5;" )
            return
        }
    
        boolean exportMode = exportButton.isSelected()
        def nGeoJson = countGeoJsonFiles( folderPath )
    
        if (nGeoJson > 0) {
            folderStatusLabel.setText( "${nGeoJson} .geojson file(s) found in selected folder" )
            folderStatusLabel.setStyle(
                exportMode
                    ? "-fx-text-fill: red;"
                    : "-fx-text-fill: green;"
            )
        } else {
            folderStatusLabel.setText( "No .geojson files found in selected folder" )
            folderStatusLabel.setStyle(
                exportMode
                    ? "-fx-text-fill: green;"
                    : "-fx-text-fill: red;"
            )
        }
    }
    
    def browseButton = new Button( "Browse..." )
    setTT( browseButton, "Select folder" )
    def selectedFolderPath = prePrefsMap.selectedFolder ?: null
    
    def browseButtonWidth = getTextWidth( browseButton.getText(), browseButton.getFont() )
    browseButton.setPrefWidth( browseButtonWidth * 1.5 )
    browseButton.setMinWidth( browseButtonWidth * 1.5 )
    browseButton.setMaxWidth( browseButtonWidth * 1.5 )
    
    // Making a binding to stop from importing from an empty folder
    def hasGeoJsonFiles = Bindings.createBooleanBinding(
        { if (!selectedFolderPath) return false
          return countGeoJsonFiles( selectedFolderPath ) > 0 }
    )
    
    // Allowing a folder to be browsed for and making updates after
    browseButton.setOnAction {
        def chooser = new DirectoryChooser()
        chooser.setTitle( "Choose folder" )

        if (selectedFolderPath) {
            def prevDir = new File( selectedFolderPath )
            if (prevDir.exists() && prevDir.isDirectory()) {
                chooser.setInitialDirectory( prevDir )
            }
        } else {
            chooser.setInitialDirectory( projectDirectory )
        }
    
        def owner  = dialog.getDialogPane().getScene()?.getWindow()
        def chosen = chooser.showDialog( owner )
        if (chosen != null) {
            selectedFolderPath = chosen.getAbsolutePath()
            hasGeoJsonFiles.invalidate()
            folderPathLabel.setText( formatFolderPathView( selectedFolderPath, folderPathLabelWidth, folderPathLabelFont ) )
            setTT( folderPathLabel, selectedFolderPath )
            updateFolderStatus( selectedFolderPath )
            folderPathLabel.setStyle( "-fx-opacity: 0.8;" )
        }
    }
    
    updateFolderStatus( selectedFolderPath )
    folderRow.getChildren().addAll( folderTitleLabel, folderPathLabel, browseButton )
    
    
    
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
    BorderPane.setMargin( pathClassBox.box, new Insets( 0, 15, 0, 0 ) )
    pathClassBox.CBs.each { it.setSelected( it.getUserData() in prePrefsMap.selectedPathClasses ) }
    
    root.right = pathClassBox.box
    
    
    
    // SECOND TO TOP ROW: mode, remove objects, include parents buttons
    // Setting the text on the confirm button to which mode is pre-selected (import vs export)
    def preSelectedModeBtn = modeButtons.getSelectedToggle() as RadioButton
    okBtn.setText( preSelectedModeBtn.getText() )
    
    // Spacer pushes Include Parents to far right
    def modeSpacer = new Region()
    HBox.setHgrow( modeSpacer, Priority.ALWAYS )
    
    // Include Parents checkbox
    def includeParentsCheck = new CheckBox( "Include Parents" )
    includeParentsCheck.setSelected( true ) // Default to true
    
    // Initial tooltip hint
    def exportParentsTTtext = "Add parent info to objects' metadata before export (so hierarchy can be restored when re-imported)"
    def importParentsTTtext = "Use saved parent info in metadata to restore hierarchy (only if exported from here)"
    setTT( includeParentsCheck, exportButton.isSelected() ? exportParentsTTtext : importParentsTTtext )
    
    if (prePrefsMap.mode == "import" || importButton.isSelected()) {
        modeButtons.selectToggle( importButton )
        pathClassBox.box.setVisible( false )
    }
    
    // Updates when mode is switched between import and export
    modeButtons.selectedToggleProperty().addListener { obs, oldToggle, newToggle ->
        boolean exportSelected = (newToggle == exportButton)
        
        okBtn.setText( exportSelected ? "Export" : "Import" )
        
        pathClassBox.box.setVisible( exportSelected )     
        setTT( includeParentsCheck, exportSelected ? exportParentsTTtext : importParentsTTtext )  
        updateFolderStatus( selectedFolderPath )
    }
    
    // Creating row and adding children
    def secondRow = new HBox( 10 )
    secondRow.getChildren().addAll(
        modeLabel,
        exportButton,
        importButton,
        modeSpacer,
        includeParentsCheck
    )
    
    // Creating container for both top rows and adding children
    def topContainer = new VBox( 8 )
    topContainer.setPadding( new Insets( 10 ) )
    def folderBox = new VBox( 2 )
    folderBox.getChildren().addAll( folderRow, folderStatusRow )  
    topContainer.getChildren().addAll( folderBox, secondRow )
    
    root.top = topContainer
    
    
    
    // Disabling the "OK" button if not all inputs are met    
    def anySelectedImage = Bindings.createBooleanBinding(
        { imagesBox.CBs.any { it.isSelected() } },
        *imagesBox.CBs.collect { it.selectedProperty() }
    )
    def anySelectedPathClass = Bindings.createBooleanBinding(
        { pathClassBox.CBs.any { it.isSelected() } },
        *pathClassBox.CBs.collect { it.selectedProperty() }
    )
    okBtn.disableProperty().bind(
        folderPathLabel.textProperty().isEqualTo( "No folder selected" )
        .or( anySelectedImage.not() )
        .or( exportButton.selectedProperty()
                .and( anySelectedPathClass.not() ) )
        .or( importButton.selectedProperty()
                .and( hasGeoJsonFiles.not() ) )
    )
    
    // Disabling the "Nest existing objects" button if no images are selected
    resolveHierarchyBtn.disableProperty().bind(
        anySelectedImage.not()
    )
    
    
    
    // Attach root & show dialog
    dialog.getDialogPane().content = root
    
    def dlgResult = dialog.showAndWait()
    if (dlgResult.isPresent()) {
        def button = dlgResult.get()
        
        if (button in [ButtonType.OK, resolveHierarchyBtnType]) {                
            def selectedImages = imagesBox.CBs
                    .findAll { it.isSelected() }
                    .collect { it.getText() }
                    
            def selectedPathClasses = pathClassBox.CBs
                    .findAll { it.isSelected() }
                    .collect { it.getUserData() }
                    
            // Determine mode from selected toggle buttons
            def selectedModeBtn = modeButtons.getSelectedToggle()
            def mode = ((button == resolveHierarchyBtnType) ? "resolveHierarchy" : (selectedModeBtn == exportButton) ? "export" : "import")
            
            // Return all preferences of "OK" is selected
            return [
                selectedFolder      : selectedFolderPath?.toString(),
                mode                : mode,
                includeParents      : includeParentsCheck.isSelected(),
                selectedImages      : selectedImages,
                currentImage        : currentImageCB.isSelected(),
                selectedPathClasses : selectedPathClasses
            ]
            
        } else {
            return null
        }
    }
}


// Exit if cancelling
if (codePreferences == null) {
    return
    
} else if (codePreferences.mode != "resolveHierarchy") { // Write to .json if exporting/importing
    def currentPrefs = [
        selectedFolder      : codePreferences.selectedFolder ?: "",
        mode                : codePreferences.mode ?: "export",
        selectedImages      : codePreferences.selectedImages ?: [],
        currentImage        : codePreferences.currentImage ?: false,
        selectedPathClasses : codePreferences.selectedPathClasses ?: []
    ]
    
    def outputGson = new GsonBuilder().setPrettyPrinting().create()
    prefsFile.text = outputGson.toJson( currentPrefs )
}


def imagesToRun = project.getImageList().findAll { it.getImageName() in codePreferences.selectedImages }

def total = imagesToRun.size()
def progressUI = createProgressWindow(
    (codePreferences.mode == "resolveHierarchy") ? "Nesting objects..." : 
    (codePreferences.mode == "export") ? "Exporting..." : 
    "Importing...", total)
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

// Checks on removing current objs
boolean removeObjs = false
boolean confirmRemoveObjsChoice = false

imageLoop:
for (image in imagesToRun) { 
    
    if (cancelFlag.get()) break
    
    boolean skipImage = false
    boolean nestingProblem = false
    
    // If working on current image, modifying respective image data
    def imageData = image == currentEntry ? currentImageData : image.readImageData()
    
    def hierarchy = imageData.getHierarchy()
    def imageName = image.getImageName()
    
    // Helper: Create a simplified image-based filename
    def makeFileName = {        
        return imageName
    }
    
    // Reporting current image
    updateProgressWindow( image: image.toString() )
    
    
    // --- Execution ---
    def fileName = makeFileName()
    def filePath = buildFilePath( codePreferences.selectedFolder, fileName + ".geojson" )
    def folder = new File( codePreferences.selectedFolder )
    def file = new File( filePath )
    
    
    // Fixing hierarchy if desired
    if (codePreferences.mode == "resolveHierarchy") {
        updateProgressWindow( stepUpdate: "Resolving hierarchy" )
        def objectsToresolve = hierarchy.getAnnotationObjects()
        resolveHierarchy( objectsToresolve, hierarchy, image )
        
    } else if (codePreferences.mode == "export") {
        
        updateProgressWindow( stepUpdate: "Exporting" )
        
        // If file exists, giving warning and option to skip/overwrite/exit
        if (file.exists()) {
            
            def cancelScriptBtn = new ButtonType( "Exit", ButtonBar.ButtonData.CANCEL_CLOSE )
            def skipFileBtn     = new ButtonType( "Skip image", ButtonBar.ButtonData.HELP )
            def okWarningBtn    = new ButtonType( "Overwrite", ButtonBar.ButtonData.HELP_2 )
            
            def existingFileDecision = showAlert(
                title: "File already exists",
                header: imageName,
                buttons: [cancelScriptBtn, skipFileBtn, okWarningBtn],
                defaultBtn: skipFileBtn,
                fallback: cancelScriptBtn
            )
            
            // Handling user decision logic
            switch (existingFileDecision) {
                case (cancelScriptBtn):
                    break imageLoop
                case (skipFileBtn):
                    skipImage = true
                    break
                case (okWarningBtn): // Overwrite and continue script
                    break
            }
        }
        
        // --- Filter annotations ---
        def annotationsForExport = hierarchy.getAnnotationObjects().findAll {
            def matchClass = it.getPathClass()?.getName() in codePreferences.selectedPathClasses
            return matchClass
        }
        
        // --- Add parent metadata if enabled ---
        def finalObjects = codePreferences.includeParents ? annotationsForExport.collect { obj ->
            def parent = obj.getParent()
            if (parent && !parent.isRootObject()) { 
                obj.getMetadata().put( "Parent_ID",   parent.getID().toString() )
                obj.getMetadata().put( "Parent_Name", parent.getName() )
            }
            return obj
        } : annotationsForExport
        
        // --- Option to skip image if no objects to export ---
        if (finalObjects.isEmpty() && !skipImage) {
            
            def exitBtn        = new ButtonType( "Exit", ButtonData.CANCEL_CLOSE )
            def skipBtn        = new ButtonType( "Skip image", ButtonData.HELP ) 
            def exportEmptyBtn = new ButtonType( "Export empty file", ButtonData.HELP )
            
            def exportOrSkip = showAlert(
                title: "No objects to export",
                header: imageName,
                buttons: [exitBtn, skipBtn, exportEmptyBtn],
                defaultBtn: exportEmptyBtn,
                fallback: exitBtn
            )
            
            // Handling user decision logic
            switch (exportOrSkip) {
                case (exitBtn):
                    break imageLoop
                case (skipBtn):
                    skipImage = true
                    break
                case (exportEmptyBtn):
                    skipImage = false
                    break
            }
        }
        
        // --- Exporting if objects present ---
        if (!skipImage) {
            exportObjectsToGeoJson( finalObjects, filePath, "FEATURE_COLLECTION" )
        }
        
        
    } else if (codePreferences.mode == "import") {
        
        updateProgressWindow( stepUpdate: "Importing" )
        
        // If cannot locate file, asking if user wants to manually locate
        if (!file.exists()) {
            
            searchLoop:
            while (true) {
            
                def fileCancelBtn = new ButtonType( "Cancel", ButtonData.CANCEL_CLOSE )
                def skipBtn       = new ButtonType( "Skip", ButtonData.HELP_2 )
                def browseBtn     = new ButtonType( "Browse...", ButtonData.HELP )
                
                def findFilePreference = showAlert(
                    title: "File not found",
                    header: imageName,
                    content: "Locate file?",
                    buttons: [fileCancelBtn, skipBtn, browseBtn],
                    defaultBtn: browseBtn,
                    fallback: fileCancelBtn
                )
                
                // Handling user decision logic
                switch (findFilePreference) {
                    
                    case (fileCancelBtn):
                        break imageLoop
                    
                    case (skipBtn):
                        skipImage = true
                        break searchLoop
                    
                    case (browseBtn):
                        def selectedFile = runOnFxThreadAndWait {
                            
                            // user clicked Browse..., open file chooser
                            def folderPath  = new File( codePreferences.selectedFolder )
                            def fileChooser = new FileChooser()
                            fileChooser.setTitle( "Find: ${imageName}" )
                            fileChooser.getExtensionFilters().add(
                                new FileChooser.ExtensionFilter("GeoJSON files", "*.geojson")
                            )
                            fileChooser.setInitialDirectory( folderPath )
                            
                            def owner      = QuPathGUI.getInstance().getStage()
                            def fileChoice = fileChooser.showOpenDialog( owner )
                            
                            return fileChoice
                        }
                        
                        if (selectedFile instanceof File) {
                            file = selectedFile
                            break searchLoop
                        }
                }
            }
        }
        
        
        // -- Importing objects if image is not being skipped --
        if (!skipImage) {
            
            // Local snapshot of what already exists in the image
            def workingObjects = hierarchy.getAnnotationObjects().toList()
            
            // Read imported objects
            def importedObjects = PathIO.readObjects( file )
            
            // Optionally remove current objects of the same class
            def importingObjClasses = importedObjects*.getPathClass().unique()
            def existingObjsSameClasses = hierarchy.getAnnotationObjects().findAll {
                it.getPathClass() in importingObjClasses
            }
            
            // If objects of same class as imported ones exist, prompt to remove or keep once
            if (!existingObjsSameClasses.isEmpty()) {
                    
                if (!confirmRemoveObjsChoice) {
                    
                    def exitBtn   = new ButtonType( "Exit", ButtonData.CANCEL_CLOSE )
                    def keepBtn   = new ButtonType( "Keep", ButtonData.HELP )
                    def removeBtn = new ButtonType( "Remove", ButtonData.HELP )
                    
                    def overlappingClasses = new HashSet()
                    existingObjsSameClasses.each { overlappingClasses.add( it.getPathClass() ) }
                    
                    def confirmRemoveObjs = showAlert(
                        title: "Potential duplicate objects!",
                        header: "There are existing objects: ${overlappingClasses}" + 
                                "\nRemove existing objects for rest of import?" +
                                "\nWARNING: Removed objects and their children cannot be recovered.",
                        buttons: [exitBtn, keepBtn, removeBtn],
                        defaultBtn: removeBtn,
                        fallback: exitBtn
                    )
                    
                    // Handling user decision logic
                    switch (confirmRemoveObjs) {
                        case (exitBtn):
                            break imageLoop
                        case (keepBtn):
                            removeObjs = false
                            confirmRemoveObjsChoice = true
                            break
                        case (removeBtn):
                            removeObjs = true
                            confirmRemoveObjsChoice = true
                            break
                    }
                } 
                
                if (removeObjs) {
                    hierarchy.removeObjects( existingObjsSameClasses, false )
                    workingObjects.removeAll( existingObjsSameClasses )
                }
            }
            
            // Add all imported objects first with no nesting yet
            importedObjects.each { obj ->
                hierarchy.addObject( obj, false )
                obj.setLocked( true )
                workingObjects << obj
            }
            
            // Resolving hierarchy if desired
            if (codePreferences.includeParents) { 
                resolveHierarchy( workingObjects, hierarchy, image )
            }
        }
    }
    
    
    // Updating viewer and saving (when relevant)
    if (codePreferences.mode in ["resolveHierarchy", "import"]) {
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
import javafx.scene.text.Text
import javafx.scene.text.Font
import javafx.stage.*
import javafx.beans.binding.Bindings
import com.google.gson.GsonBuilder
import com.google.gson.Gson


// Comments for tool launcher icon and tooltip
// ICON: EXCHANGE
// Export or import annotations with parents