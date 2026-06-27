/*
 * Author: Alec McKendell
 * Date: 2026.2.4
 * Description: Manually classify many objects
*/

// --- Parameters ---
def dialogOffsetX = 25
def dialogOffsetY = 25


// --- Classes ---
// Define once at the top of the script
class ViewerSessionVars {
    List<String> classifiers
    List<String> reviewers
    def          viewer
    def          view
    def          overlayOptions
    String       userName
    String       userKey
    String       classifyingName
    List         recentClassifyingNames
    List<String> classifierKeys
    String       toolKeyPrefix
    String       annoClass
    int          gridSize
    def          hierarchy
    def          allClassAnnos
    boolean      checkingWork
    def          channelToChange
    def          brightnessInfo
    boolean      freeView
    String       freeViewMode
    List         freeViewModes
    def          nonZoomedViewerState
    String       scrollFilterKey
    boolean      instructions
    boolean      zoomed
    def          showing
    def          annoStateMap
    def          statesAndColors
    def          channelSpecifics
    def          controllableChannels
    def          prevSelectedAnnos
    double       pixelSize
    def          scrollBlocker
    double       oldAnnoThickness
    List<String> reviewStateFreqs
    List<String> reviewStates
    Map          reviewerCriteria
    String       message
    String       lastMessage
    long         lastMessageTime
    String       toggleChannel
    List         toggleChannels
    String       annosToCheck
    List<String> reviewerStates
    String       reviewerClassification
    Map          codePreferences
    def          prefsFile
    List         imagesToProcess
    Map          frequencies
    Map          currentAnnoColorsMap
    List         highlightStatesAndColorsMap
    
    // Making mode variables that are permanently opposite
    private boolean _classifierMode = false
    
    boolean getClassifierMode() { _classifierMode }
    boolean getReviewerMode()   { !_classifierMode }
    
    void setClassifierMode( boolean val ) {
        _classifierMode = val
    }
    void setReviewerMode( boolean val ) {
        _classifierMode = !val
    }
}

def session = new ViewerSessionVars(
    toolKeyPrefix               : "AT-manual",
    currentAnnoColorsMap        : [:],
    highlightStatesAndColorsMap : []
)

// Class for actions so they aren't passed as strings
class ActionStrings {
    static final String CLASSIFIER_MODE       = "classifier"
    static final String REVIEWER_MODE         = "reviewer"
    static final String CANCEL                = "Cancel"
    static final String NEXT                  = "Next"
    static final String BACK                  = "Back"
    static final String REMOVE                = "Remove"
    static final String YES                   = "Yes"
    static final String NO                    = "No"
    static final String TOGGLE_FREE_VIEW      = "ToggleFreeView"
    static final String SWITCH_FREE_VIEW_MODE = "SwitchFreeViewMode"
    static final String TOGGLE_ANNOS          = "ToggleAnnos"
    static final String TOGGLE_FOCUS          = "ToggleFocus"
    static final String TOGGLE_CLASS          = "ToggleClassification"
    static final String SET_CLASS             = "SetClassification"
    static final String TOGGLE_CHANNEL        = "ToggleChannel"
    static final String CENTER_VIEWER         = "CenterViewer"
    static final String BRIGHT_UP             = "BrightUP"
    static final String BRIGHT_DOWN           = "BrightDOWN"
    static final String INSTRUCTIONS          = "Instructions"
    static final String ZOOM                  = "zoom"
    static final String BRIGHTNESS            = "brightness"
    static final String BACK_TO_LAST          = "Back to last"
    static final String REVIEW_ALL            = "Review all"
}

// Class for UI elements to update when needed
class elements {
    GridPane grid
    List     btnMap
    VBox     headerBox
    Map      channelEntryByChanName
}

def uiElements = new elements()


// --- Functions ---

// FX-safe helper function
def runOnFxThreadAndWait( Closure fxCode ) {
    def latch = new CountDownLatch( 1 )
    def result = [value: null]
    Platform.runLater {
        try { result.value = fxCode.call() }
        finally { latch.countDown() }
    }
    latch.await()
    return result.value
}

// Function to set or change a tooltip hint for one or multiple objects
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

// Function to get euclidean distance between two packed RGB ints (ignores alpha)
def colorDistance( int c1, int c2 ) {
    def r1 = (c1 >> 16) & 0xFF
    def g1 = (c1 >> 8)  & 0xFF
    def b1 =  c1        & 0xFF
    def r2 = (c2 >> 16) & 0xFF
    def g2 = (c2 >> 8)  & 0xFF
    def b2 =  c2        & 0xFF
    Math.sqrt( (r1-r2)**2 + (g1-g2)**2 + (b1-b2)**2 )
}

// Function to generate visible color far from other existing colors
def generateVisibleColor( List existingColors, List reservedColors, double minDistance = 80.0 ) {
    Random rng = new Random()
    int newColor
    int attempts = 0

    def allColors = (existingColors + reservedColors).unique()

    do {
        float hue        = rng.nextFloat()
        float saturation = 0.55f + rng.nextFloat() * 0.35f
        float lightness  = 0.35f + rng.nextFloat() * 0.25f

        newColor = AwtColor.HSBtoRGB( hue, saturation, lightness )

        attempts++
        if (attempts > 500) break  // safety valve if palette is nearly exhausted

    } while (
        newColor in allColors ||
        allColors.any { colorDistance(newColor, it) < minDistance }
    )

    return newColor
}

// Function to stop nodes from becoming highlighted when clicked
def disableNonEditableFocusTraversal( Node node ) {
    
    node.setFocusTraversable( false )
    
    if (node instanceof Parent) {
        node.getChildrenUnmodifiable().each {
            disableNonEditableFocusTraversal( it )
        }
    }
}

// Function to make instructions and add circles of specific color if wanted (add null to make new line)
def makeLines( List lineSpec ) {
    def flatLine = lineSpec.flatten()
    def listOfLines = []
    
    def line = new HBox( 8 )
    line.setAlignment( Pos.CENTER_LEFT )
    
    flatLine.each { item ->
        if (item == null) {
            // end current line & start a new one
            if (!line.getChildren().isEmpty()) { listOfLines << line }
            line = new HBox( 8 )
            line.setAlignment( Pos.CENTER_LEFT )
            
        } else if (item instanceof Color) {
            def circle = new Circle( 6 )
            circle.setStroke( item )
            circle.setStrokeWidth( 2 )
            circle.setFill( Color.TRANSPARENT )
            line.getChildren().add( circle )
            
        } else {
            def label = new Label( item.toString() )
            label.setStyle( "-fx-font-size: 1.1em;" )
            line.getChildren().add( label )
        }
    }
    
    // add the last line if it has content
    if (!line.getChildren().isEmpty()) { listOfLines << line }
    
    return listOfLines
}

// Function to generate instruction lines with colored circles
def makeStateColorInstructions( List map ) {
    def instructionList = []
    boolean containsColorU = map.any { it.containsKey( "colorU" ) }
    
    map.eachWithIndex { it, i ->
        instructionList << null
        instructionList << "       ${i+1}. "
        instructionList << intToFx( it.color )
        if (it.containsKey( "colorU" )) {
            instructionList << "/"
            instructionList << intToFx( it.colorU )
            instructionList << " -> ${it.state}"
        } else {
            instructionList << (containsColorU ? "          -> ${it.state}" : " -> ${it.state}")
        }
        if (it.containsKey( "count" )) {
            instructionList << "(${it.count})"
        }
        instructionList << null
    }
    return instructionList
}

// Function for buttons and response
def buildAndWireAlertUI( Alert alert, ViewerSessionVars session, elements uiElements, queue ) {
    uiElements.grid = new GridPane()
    uiElements.grid.setHgap( 10 )
    uiElements.grid.setVgap( 10 )
    uiElements.grid.setPadding( new Insets( 10 ) )
    uiElements.grid.setAlignment( Pos.CENTER )
    alert.getDialogPane().setContent( uiElements.grid )
    
    
    // --- Adding buttons, map, and shortcuts ---
    uiElements.btnMap = []
    
    uiElements.btnMap << [ name   : ActionStrings.CANCEL,
                           action : { queue.put( ActionStrings.CANCEL ) },
                           btnType: new ButtonType( "(C)ancel", ButtonData.HELP_2 ),
                           key    : ["c", (KeyCode.ESCAPE)],
                           active : false]
                           
    uiElements.btnMap << [ name   : ActionStrings.INSTRUCTIONS,
                           action : { queue.put( ActionStrings.INSTRUCTIONS ) },
                           key    : "i",
                           active : false ]
                           
    uiElements.btnMap << [ name   : ActionStrings.TOGGLE_ANNOS,
                           action : { queue.put( ActionStrings.TOGGLE_ANNOS ) },
                           key    : "a",
                           click  : MouseButton.MIDDLE,
                           active : false ]
                           
    uiElements.btnMap << [ name   : ActionStrings.CENTER_VIEWER,
                           action : { queue.put( ActionStrings.CENTER_VIEWER ) },
                           click  : MouseButton.PRIMARY,
                           active : false ]
    
    uiElements.btnMap << [ name   : ActionStrings.TOGGLE_FOCUS,
                           action : { queue.put( ActionStrings.TOGGLE_FOCUS ) },
                           click  : MouseButton.SECONDARY,
                           active : false ]
                           
    uiElements.btnMap << [ name   : ActionStrings.REMOVE,
                           action : { queue.put( ActionStrings.REMOVE ) },
                           btnType: new ButtonType( "(R)emove All", ButtonData.HELP_2 ),
                           btnTT  : "Remove current user's classifications from all objects in current group",
                           key    : "r",
                           active : false ]
                           
    uiElements.btnMap << [ name   : ActionStrings.BACK,
                           action : { queue.put( ActionStrings.BACK ) },
                           btnType: new ButtonType( "(B)ack", ButtonData.BACK_PREVIOUS ),
                           btnTT  : "Go back to previous ${session.classifierMode ? 'group' : 'annotation'}",
                           key    : "b",
                           click  : MouseButton.BACK,
                           active : false ]
                           
    uiElements.btnMap << [ name   : ActionStrings.NEXT,
                           action : { queue.put( ActionStrings.NEXT ) },
                           btnType: new ButtonType( "(N)ext", ButtonData.YES ),
                           btnTT  : "Lock in current classifications and move to next group",
                           key    : "n",
                           click  : MouseButton.FORWARD,
                           active : false ]
                           
    uiElements.btnMap << [ name   : ActionStrings.TOGGLE_CLASS,
                           action : { queue.put( ActionStrings.TOGGLE_CLASS ) },
                           click  : MouseButton.PRIMARY,
                           active : false ]
                           
    // Reviewer classification buttons
    session.reviewerStates.eachWithIndex { state, idx ->
        String numberTrigger = (idx + 6)
        String btnText = "(${numberTrigger}) ${state}"
        def revBtnType = new ButtonType( btnText, ButtonData.RIGHT )
        int classColorInt = session.statesAndColors.find { it.state == state }.color
        def c = new AwtColor( classColorInt, true )
        def btnStyle = "-fx-effect: dropshadow(gaussian, rgba(${c.red},${c.green},${c.blue},1), 3, 0.4, 0, 0)"
        
        String btnTT = "Set classification to \"${state}\""
        
        def action = {
            session.reviewerClassification = state
            queue.put( ActionStrings.SET_CLASS )
        }
        
        uiElements.btnMap << [
            name    : btnText,
            btnType : revBtnType,
            btnTT   : btnTT,
            btnStyle: btnStyle,
            key     : numberTrigger,
            action  : action,
            group   : "revClass",
            active  : false
        ]
    }
    
    // Channel-specific buttons
    session.channelSpecifics.eachWithIndex { ch, idx ->
        String numberTrigger = (idx + 1)
        
        // Creating and adding them
        String btnText = "(${idx+1}) ${ch.name}"
        ToggleButton btn = new ToggleButton( btnText )
        
        // Setting their functions
        def action = {
            session.toggleChannel = ch.name
            queue.put( ActionStrings.TOGGLE_CHANNEL )
        }
        
        // Adding to button map
        uiElements.btnMap << [
            name  : btnText,
            chName: ch.name,
            btn   : btn,
            btnPos: [idx, 2],
            key   : numberTrigger,
            action: action,
            group : "channel",
            active: false
        ]
    }
    
    uiElements.channelEntryByChanName = uiElements.btnMap
        .findAll { it.group == "channel" }
        .collectEntries { [(it.chName): it] }
    
    // Wire ButtonType-backed buttons
    List btnTypes = uiElements.btnMap.findAll { it.containsKey( "btnType" ) }.btnType
    alert.getButtonTypes().setAll( btnTypes )

    uiElements.btnMap.each {
        if (it.containsKey( "btnType" )) { it.btn = alert.getDialogPane().lookupButton( it.btnType ) }
        if (it.containsKey( "btnTT" ))   { setTT( it.btn, it.btnTT ) }
        if (it.group == "revClass")      { ButtonBar.setButtonUniformSize( it.btn, false ) }
        if (it.containsKey( "btnStyle" )){ it.btn.setStyle( it.btnStyle ) }
    }

    // --- Wire button actions ONCE, guard with active flag ---
    uiElements.btnMap.each { entry ->
        if (!entry.btn) return
        if (entry.btn instanceof ToggleButton) {
            entry.btn.setOnAction { if (entry.active) entry.action() }
        } else {
            entry.btn.addEventFilter( ActionEvent.ACTION ) { event ->
                if (entry.active) entry.action()
                event.consume()
            }
        }
    }

    // --- Keyboard shortcuts ONCE ---
    def scene = alert.getDialogPane().getScene()

    def shiftDown        = new AtomicBoolean( false )
    def shiftLongPressed = new AtomicBoolean( false )
    // Single PauseTransition, reused
    def shiftHoldTimer   = new PauseTransition( Duration.millis( 500 ) )
    shiftHoldTimer.setOnFinished {
        if (shiftDown.get() && !shiftLongPressed.get()) {
            shiftLongPressed.set( true )
            queue.put( ActionStrings.TOGGLE_FREE_VIEW )
        }
    }

    scene.setOnKeyPressed { event ->
        def key = event.getText() ? event.getText().toLowerCase() : event.getCode()

        if (key == KeyCode.SHIFT) {
            if (shiftDown.getAndSet( true )) return
            shiftLongPressed.set( false )
            shiftHoldTimer.playFromStart()
            return
        }

        // Dynamic lookup — no rewiring needed on mode change
        uiElements.btnMap.find { it.active && key in it.key }?.action()
    }

    scene.setOnKeyReleased { event ->
        if (event.getCode() == KeyCode.SHIFT) {
            shiftHoldTimer.stop()
            if (shiftDown.get() && !shiftLongPressed.get() && session.freeView) {
                queue.put( ActionStrings.SWITCH_FREE_VIEW_MODE )
            }
            shiftDown.set( false )
        }
    }

    // --- Mouse dispatcher ONCE ---
    def viewScene          = session.view.getScene()
    def originalDispatcher = viewScene.getEventDispatcher()  // captured once, never stale

    def handledClickEvent  = new AtomicBoolean( false )
    def handledScrollEvent = new AtomicBoolean( false )

    viewScene.setEventDispatcher( new EventDispatcher() {
        Event dispatchEvent( Event event, EventDispatchChain tail ) {
            if (alert.isShowing() && (event instanceof MouseEvent || event instanceof ScrollEvent)) {

                if (!handledClickEvent.get() && event instanceof MouseEvent
                        && event.getEventType() == MouseEvent.MOUSE_PRESSED) {
                    uiElements.btnMap.find { it.active && it.click == event.getButton() }?.action()
                    handledClickEvent.set( true )
                }

                if (event instanceof MouseEvent && event.getEventType() == MouseEvent.MOUSE_RELEASED) {
                    handledClickEvent.set( false )
                }

                if (!handledScrollEvent.get() && event instanceof ScrollEvent) {
                    if (session.freeView && session.freeViewMode == ActionStrings.ZOOM) {
                        return originalDispatcher.dispatchEvent( event, tail )
                    } else if (Math.abs( event.getDeltaY() ) >= 1) {
                        queue.put( event.getDeltaY() > 0 ? ActionStrings.BRIGHT_UP : ActionStrings.BRIGHT_DOWN )
                        handledScrollEvent.set( true )
                    }
                }

                if (event instanceof ScrollEvent) { handledScrollEvent.set( false ) }

                event.consume()
                return event
            }
            return originalDispatcher.dispatchEvent( event, tail )
        }
    } )

    // --- Restore dispatcher on close ---
    alert.setOnHidden {
        viewScene.setEventDispatcher( originalDispatcher )
    }

    // --- Header box (built once, content replaced by updateUI) ---
    uiElements.headerBox = new VBox()
    uiElements.headerBox.setPadding( new Insets( 10 ) )
    uiElements.headerBox.setStyle("""
        -fx-background-color: -fx-box-border, -fx-control-inner-background;
        -fx-background-insets: 0, 1;
    """)
    alert.getDialogPane().setHeader( uiElements.headerBox )
}

// Function to update UI
def updateUI( Alert alert, ViewerSessionVars session, elements uiElements, queue ) { 
    
    // --- Determine active set ---
    Set availableBtns = [ActionStrings.INSTRUCTIONS, ActionStrings.CANCEL, ActionStrings.TOGGLE_ANNOS] as LinkedHashSet
    if (session.freeView) {
        availableBtns.addAll( ActionStrings.INSTRUCTIONS, ActionStrings.CENTER_VIEWER, ActionStrings.TOGGLE_FOCUS )
    } else if (session.classifierMode) {
        availableBtns.addAll( ActionStrings.REMOVE, ActionStrings.BACK, ActionStrings.NEXT, 
                              ActionStrings.TOGGLE_CLASS, ActionStrings.TOGGLE_FOCUS )
    } else if (session.reviewerMode) {
        availableBtns.addAll( ActionStrings.CANCEL, ActionStrings.TOGGLE_ANNOS, ActionStrings.BACK )
        uiElements.btnMap.findAll { it.group == "revClass" }.each { availableBtns << it.name }
    }
    uiElements.btnMap.findAll { it.group == "channel" }.each { availableBtns << it.name }

    // --- Diff and update ---
    List previouslyActive = uiElements.btnMap.findAll { it.active }.name

    uiElements.btnMap.each { it.active = (it.name in availableBtns) }

    uiElements.btnMap.each {
        it.btn?.setVisible( it.active )
        it.btn?.setManaged( it.active )
    }

    // Grid membership: only touch entries that changed
    uiElements.btnMap.findAll { it.containsKey( "btnPos" ) }.each {
        boolean wasActive = it.name in previouslyActive
        if (wasActive && !it.active) { uiElements.grid.getChildren().remove( it.btn ) }
        if (!wasActive && it.active) { uiElements.grid.add( it.btn, *it.btnPos ) }
    }
    
    
    // Creating button styles
    def standardStyle = """
        -fx-padding: 6 10 6 10;
        -fx-border-radius: 6;
        -fx-background-radius: 6;
        -fx-border-color: #666666;
        -fx-border-width: 1.5;
    """.stripIndent()
    
    def highlightedStyle = { chan -> // Channel to change brightness style
        def c = new AwtColor( chan.getColor(), true )
        return "-fx-effect: dropshadow(gaussian, rgba(${c.red},${c.green},${c.blue},1), 10, 0.2, 0, 0);"
    }
    def selectedStyle = "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 10, 0.2, 0, 0);" // Channel on style
    def unselectedStyle = "-fx-effect: innershadow(gaussian, rgba(0,0,0,0.9), 100, 0, 0, 0);" // Channel off style
    
    // - Channel-specific buttons -
    List chBtns = uiElements.btnMap.findAll { it.group == "channel" }
    session.channelSpecifics.eachWithIndex { ch, i ->
        
        // Creating and adding them
        def entry = uiElements.channelEntryByChanName[ ch.name ]
        def btn = entry.btn
        
        btn.setSelected( ch.state )
        
        // Adding their style
        boolean highlighted = (ch.channel == session.channelToChange) && (!session.freeView || session.freeViewMode != ActionStrings.ZOOM) // Setting initial style
        def buttonStyle = highlighted ? highlightedStyle( ch.channel ) : btn.isSelected() ? selectedStyle : unselectedStyle
        btn.setStyle( standardStyle + buttonStyle )
        
        def chBtnTooltipText = highlighted ? "Toggle ${ch.name} \n \n Scroll on viewer to change brightness" : "Toggle ${ch.name}"
        setTT( btn, chBtnTooltipText )
    }
    
    
    
    String brightnessChannel = session.channelToChange ? session.channelSpecifics.find { it.channel == session.channelToChange }.name : "none"
    String scrollInstructions = session.freeViewMode == ActionStrings.ZOOM ? "Zoom" : "Brightness [${brightnessChannel}]"
    boolean annosOn = getAnnosVisibilityStatus( session )
    List revClassKeys = uiElements.btnMap.findAll { it.group == "revClass" }.key
    List channelKeys = uiElements.btnMap.findAll { it.group == "channel" }.key
    
    
    // Instruction lines
    List closedInstructionsI = ["(I) Show instructions", null]
    List openInstructionsI =   ["(I) Instructions:", null]
    List toggleAnnosI =        [" - (A) Toggle annotations [${annosOn}].", null]
    List fVscrollI =           ["       -> (SCROLL) ${scrollInstructions}.", null]
    List moveViewerI =         [" - (LEFT CLICK VIEWER) Move viewer.", null]
    List changeFocusI =        [" - (RIGHT CLICK ANNO) Change focus.", null]
    List cycleClassI =         [" - (LEFT CLICK ANNO) Cycle class/colors.", null]
    List switchFvModeI =       [" - (TAP SHIFT) Switch scroll function.", null]
    List changeBrightI =       ["       -> (SCROLL) Brightness [${brightnessChannel}].", null]
    List goBackI =             [" - (B) Return to previous ${session.reviewerMode ? 'annotation' : 'group'}.", null]
    List enterFvI =            [" - (HOLD SHIFT) Enter free view", null]
    List exitFvI =             [" - (HOLD SHIFT) Exit free view", null]
    List setRevClassI =        [" - (${revClassKeys.join(', ')}) Set classification", null]
    List toggleChannelI =      [" - (${channelKeys.join(', ')}) Toggle channel", null]
    
    List classColorsI =          makeStateColorInstructions( session.statesAndColors )
    List highlightClassColorsI = makeStateColorInstructions( session.highlightStatesAndColorsMap )
    
    
    List instructionLines
    
    // Conditionally building instructions
    if (!session.instructions) {
        instructionLines = makeLines( closedInstructionsI )
    
    } else if (session.freeView) {
        instructionLines = makeLines( [
                    openInstructionsI,
                    toggleAnnosI,
                    moveViewerI,
                    changeFocusI,
                    switchFvModeI,
                    fVscrollI,
                    exitFvI,
                    "", null,
                    "${session.classifyingName} classifications from ${session.classifierMode ? session.userName : session.classifiers}:",
                    highlightClassColorsI ] )
        
    } else if (session.reviewerMode) {
        instructionLines = makeLines( [
                    openInstructionsI,
                    toggleAnnosI,
                    goBackI,
                    toggleChannelI,
                    changeBrightI, 
                    setRevClassI, 
                    enterFvI ] )
    
    } else {
        instructionLines = makeLines( [
                    openInstructionsI,
                    toggleAnnosI,
                    goBackI,
                    toggleChannelI,
                    changeBrightI,
                    cycleClassI,
                    classColorsI,
                    changeFocusI,
                    enterFvI ] )
    }
    
    uiElements.headerBox.getChildren().setAll( instructionLines )
    
    def dialogPane = alert.getDialogPane()
    dialogPane.getScene()?.getWindow()?.sizeToScene()
    disableNonEditableFocusTraversal( dialogPane )
}

// Function for changing viewer center/zoom
def changeViewer( double centerX, double centerY, double vWidth, double vHeight, boolean isUM, ViewerSessionVars session ) {
    double width_uM = vWidth
    double height_uM = vHeight
    if (!isUM) {
        // Convert annotation size from px to µm
        width_uM  = width_uM  * session.pixelSize
        height_uM = height_uM * session.pixelSize
    }
    
    // Get viewer display area in pixels
    double viewWidthPx  = session.view.getWidth()
    double viewHeightPx = session.view.getHeight()
    // Compute µm per screen pixel needed to fit
    double umPerPxX = width_uM  / viewWidthPx
    double umPerPxY = height_uM / viewHeightPx
    double umPerPx = Math.max(umPerPxX, umPerPxY) * 1.1 // Add margin
    // Convert to magnification
    double mag = 0.25 / umPerPx
    
    // Spetial run so viewer doesn't "miss"
    Platform.runLater {   
        def settle = new PauseTransition( Duration.millis( 5 ) )
        settle.setOnFinished {          
            session.viewer.setMagnification( mag )
            session.viewer.setCenterPixelLocation( centerX, centerY )
        }
        
        settle.play()
    }
}

// Function to save the current viewer state to be restored
def saveViewerState( ViewerSessionVars session ) {
    return [
        x  : session.viewer.getCenterPixelX(),
        y  : session.viewer.getCenterPixelY(),
        mag: session.viewer.getMagnification()
    ]
}


// Function to restore the viewer state (with function above)
def restoreViewerState( viewerState, ViewerSessionVars session ) {
    if (!viewerState) return
    
    //Double running execution so viewer doesn't "miss"
    (0..1).each {
        Platform.runLater {
            session.viewer.setMagnification( viewerState.mag )
            session.viewer.setCenterPixelLocation( viewerState.x, viewerState.y )
        }
    }
}

// Function for changing viewing grid
def changeGrid( boolean turnOnGrid, double spacingX, double spacingY, ViewerSessionVars session ) {
    Platform.runLater {
        session.overlayOptions.setShowGrid( turnOnGrid )
        PathPrefs.gridSpacingX.set( spacingX )
        PathPrefs.gridSpacingY.set( spacingY )
    }
}

// Function for changing stored classification
def changeClassification( annoToChange, ViewerSessionVars session ) {
    def classifiedAs = session.annoStateMap[ annoToChange ]
    int oldIndex = session.statesAndColors.state.indexOf( classifiedAs )
    int newIndex = (oldIndex + 1) % session.statesAndColors.size()
    def newGroup = session.statesAndColors[ newIndex ]
    def newClass = newGroup.state
    def newColor = newGroup.color
    session.annoStateMap.put( annoToChange, newClass )
    
    def value = annoToChange.getMetadata().get( session.userKey )
    
    // To change the color with changing classification (pink is false when unconfirmed)
    !value && newIndex == 0 ? annoToChange.setColor( newGroup.colorU ) : annoToChange.setColor( newColor )
    
    Platform.runLater { session.viewer.repaintEntireImage() }
}

// Function for switching to next image (or remaining on current)
def getNextImage( nextImage ) {
    def thisImageData = getCurrentImageData()
    def nextImageData = nextImage.readImageData()
    
    if (getProjectEntry() != nextImage) {
        runOnFxThreadAndWait { getCurrentViewer().setImageData( nextImageData ) }
        return nextImageData
    } else {
        return thisImageData
    }
}

// Function to toggle annotation visibility or read whether they're on
def toggleAnnos( ViewerSessionVars session ) {
    boolean annosOn = session.overlayOptions.getShowAnnotations()
    Platform.runLater {
        session.overlayOptions.setShowAnnotations( !annosOn )
        if (annosOn) { resetSelection() }
    }
}

// Function to get annotation visibility
def getAnnosVisibilityStatus( ViewerSessionVars session ) {
    boolean annosOn = session.overlayOptions.getShowAnnotations()
    return annosOn
}

// Function to get cursor position (x,y)
def getCursorPosition( ViewerSessionVars session ) {
    // Get viewer JavaFX node and convert its top-left to screen coordinates
    def viewScreenPos = session.view.localToScreen( 0, 0 )
    
    // Get the mouse pointer location on screen (AWT)
    def mouseScreen = MouseInfo.getPointerInfo()?.getLocation()
    
    // Compute mouse position relative to viewer (component coordinates)
    def mouseX_inViewer = mouseScreen.getX() - viewScreenPos.getX()
    def mouseY_inViewer = mouseScreen.getY() - viewScreenPos.getY()
    
    // Convert to image pixel coordinates
    def viewerPoint = new Point2D.Double( mouseX_inViewer, mouseY_inViewer )
    def imagePoint = session.viewer.componentPointToImagePoint( viewerPoint, null, true )
    
    def cursorX = imagePoint.getX()
    def cursorY = imagePoint.getY()
    
    return [x: cursorX, y: cursorY]
}

// Function to toggle a channel's visibility
def toggleChannel( channelForToggle, ViewerSessionVars session ) {
    if (!channelForToggle) return // Skipping rest if there is no channel to change
    def display = session.viewer.getImageDisplay()
    boolean toggle = channelForToggle in display.selectedChannels()
    
    Platform.runLater {
        display.setChannelSelected( channelForToggle, !toggle )
        session.viewer.repaintEntireImage()
    }
    // Setting its new status back in lookup map
    session.channelSpecifics.find { it.channel == channelForToggle }.state = !toggle
}

// Function to change a channel's brightness
def changeBrightness( String changeDirection, ViewerSessionVars session ) {
    if (!session.channelToChange) return // Skipping rest if there is no channel to change
    
    String channelName = session.channelSpecifics.find { it.channel == session.channelToChange }.name
    
    def display = session.viewer.getImageDisplay()
    boolean repaint = true
    // Current display range
    float minDisplay = session.channelToChange.getMinDisplay()
    float maxDisplay = session.channelToChange.getMaxDisplay()
    float newMinDisplay = minDisplay
    float newMaxDisplay = maxDisplay
    // Retrieving brightness info
    float maxAvailable = session.brightnessInfo[ session.channelToChange ].maxAvailable
    float autoMinDisplay = session.brightnessInfo[ session.channelToChange ].autoMinDisplay
    def expSteps = (session.brightnessInfo[ session.channelToChange ].expSteps as List<Float>)
    def stdSteps = (session.brightnessInfo[ session.channelToChange ].stdSteps as List<Float>)
    // Helpers: previous/next value in a sorted list
    def prevVal = { List<Float> arr, float x ->
        if (!arr) return null
        int idx = Collections.binarySearch( arr, x )
        int pos = idx >= 0 ? idx : ~idx  // insertion point
        pos > 0 ? arr[pos - 1] : null
    }
    def nextVal = { List<Float> arr, float x ->
        if (!arr) return null
        int idx = Collections.binarySearch( arr, x )
        int pos = idx >= 0 ? idx + 1 : ~idx
        pos < arr.size() ? arr[ pos ] : null
    }
    // Code for changing channel brightness
    // Only changing brightness if channel is on
    if (session.channelToChange in display.selectedChannels()) {
        if (changeDirection == "UP") {
            if (minDisplay > autoMinDisplay) {
                def cand = prevVal( stdSteps, minDisplay )
                newMinDisplay = (!cand || cand < autoMinDisplay) ? autoMinDisplay : cand
            } else {
                newMaxDisplay = prevVal( expSteps, maxDisplay )
                if (!newMaxDisplay) {
                    session.message = "Maximum brightness! (${channelName})"
                    conditionallyFireMessage( session )
                    repaint = false
                }
            }
        } else if (changeDirection == "DOWN" ) {
            if (minDisplay < autoMinDisplay) {
                def cand = nextVal( stdSteps, minDisplay )
                newMinDisplay = (!cand || cand > autoMinDisplay) ? autoMinDisplay : cand
            } else {
                newMaxDisplay = nextVal( expSteps, maxDisplay )
                if (!newMaxDisplay) {
                    newMaxDisplay = maxAvailable
                    newMinDisplay = nextVal( stdSteps, minDisplay )
                    if (!newMinDisplay) {
                        session.message = "Minimum brightness! (${channelName})"
                        conditionallyFireMessage( session )
                        newMinDisplay = maxAvailable
                        repaint = false
                    }
                }
            }
        }
    }
    // Only triggering repaint if hasn't already been done
    if (repaint) {
        Platform.runLater {
            display.setMinMaxDisplay( session.channelToChange, newMinDisplay, newMaxDisplay )
            session.viewer.repaintEntireImage()
        }
    }
}

// Function to check if a single annotation matches specific metadata classification(s)
def matchesMdClassification( annoToCheck, List sameValues, List anyValues, boolean diffValues, ViewerSessionVars session ) {
    
    def md = annoToCheck.getMetadata()
    
    if (!session.classifierKeys.every { md.containsKey( it ) }) return false
    
    def vals = session.classifierKeys.collect { md.get( it ) }
    
    if (sameValues && vals.every { it == vals[0] && it in sameValues }) return true
    if (anyValues  && vals.any   { it in anyValues })                   return true
    if (diffValues && vals.toSet().size() > 1)                          return true
    
    return false
}

// Function to input and collect a list of annotations matching specific metadata classification(s)
// Utilizes the above function
def findAnnosByMdClassification( List annosToCheck, List sameValues, List anyValues, boolean diffValues, ViewerSessionVars session ) {
    
    annosToCheck.findAll { anno ->
        matchesMdClassification( anno, sameValues, anyValues, diffValues, session )
    }
}

// Function to check if an anno has userKey
def containsUserKey( annoToCheck, ViewerSessionVars session ) {
    def md = annoToCheck.getMetadata()
    return md.containsKey( session.userKey )
}

// Function to filter a list of annotations for presence of userKey (uses above function)
def filterForUserKey( List annosToCheck, boolean shouldHaveKey, ViewerSessionVars session ) {
    annosToCheck = annosToCheck.findAll { anno ->
        shouldHaveKey ? containsUserKey( anno, session ) : !containsUserKey( anno, session )
    }
}

// Function to conditionally toggle the viewer focus to/away from an annotation
def toggleFocus( ViewerSessionVars session, List annos ) {
    if (!session.zoomed) {
        def mouseCoords = getCursorPosition( session )
        
        for (anno in annos) {
            def roi = anno.getROI()
            
            // Check if the mouse point lies inside the selected ROI and focus into it if so
            if (roi.contains( mouseCoords.x, mouseCoords.y )) {
                
                session.nonZoomedViewerState = saveViewerState( session )
                
                changeViewer( roi.getCentroidX(), roi.getCentroidY(), roi.getBoundsWidth() * 2,
                              roi.getBoundsHeight() * 2, false, session )
                session.zoomed = true
                
                double[] lastZoom = [0.0]  // Declared here so zoomListener can reference it
                
                // Add a zoom listener so if zoomed after focus, it's no longer considered focues
                // Meaning right clicking again won't take back to previous viewer
                def zoomListener = new QuPathViewerListener() {
                
                    @Override
                    void visibleRegionChanged( QuPathViewer v, java.awt.Shape shape ) {
                        double currentZoom = v.getDownsampleFactor()
                        if (Math.abs( currentZoom - lastZoom[0] ) > 0.1) {
                            v.removeViewerListener( this )
                            session.zoomed = false
                        }
                    }
                
                    @Override
                    void imageDataChanged( QuPathViewer v, ImageData before, ImageData after ) {}
                    @Override
                    void selectedObjectChanged( QuPathViewer v, PathObject pathObj ) {}
                    @Override
                    void viewerClosed( QuPathViewer v ) {
                        v.removeViewerListener( this )
                    }
                }
                
                // Small pause to track only when zoom actually changes
                PauseTransition pause = new PauseTransition( Duration.millis( 200 ) )
                pause.setOnFinished {
                    lastZoom[0] = session.viewer.getDownsampleFactor()
                    session.viewer.addViewerListener( zoomListener )
                }
                pause.play()
                
                break
            }
        }
    } else {
        restoreViewerState( session.nonZoomedViewerState, session )
        session.zoomed = false
    }
}

// Function to add a scroll blocker (stops qupath from zooming in viewer)
def addScrollBlocker( ViewerSessionVars session ) {
    if (!session.view.properties[ session.scrollFilterKey ]) {
        session.view.addEventFilter( ScrollEvent.ANY, session.scrollBlocker )
        session.view.properties[ session.scrollFilterKey ] = true
    }
}

// Function to remove a scroll blocker (stops qupath from zooming in viewer)
def removeScrollBlocker( ViewerSessionVars session ) {
    session.view.removeEventFilter( ScrollEvent.ANY, session.scrollBlocker )
    session.view.properties.remove( session.scrollFilterKey )
}

// Function to switch fre view modes
def switchFVmode( ViewerSessionVars session ) {
    int freeViewModeIdx = session.freeViewModes.indexOf( session.freeViewMode )
    session.freeViewMode = session.freeViewModes[ (freeViewModeIdx + 1) % session.freeViewModes.size() ]     
    
    Platform.runLater {
        if (session.freeViewMode == ActionStrings.ZOOM && session.view.properties[ session.scrollFilterKey ]) {
            removeScrollBlocker( session )
        } else if (session.freeViewMode == ActionStrings.BRIGHTNESS && !session.view.properties[ session.scrollFilterKey ]) {
            addScrollBlocker( session )
        }
    }
}

// Function to return QuPath to pre-script state
def restoreToPrescript( ViewerSessionVars session ) {
    Platform.runLater { 
        session.overlayOptions.setShowAnnotations( true )
        removeScrollBlocker( session )
        PathPrefs.annotationStrokeThicknessProperty().set( session.oldAnnoThickness )
        session.overlayOptions.setOpacity( 1 )
    }
    
    def addedDets = session.hierarchy.getDetectionObjects()
    session.hierarchy.removeObjects( addedDets, true ) 
    session.allClassAnnos.each { it.setColor( null ) }
    fireHierarchyUpdate() 
}

// Function to generate classifier/reviewer key for metadata
def generateMdKey( ViewerSessionVars session, String user, String classKey ) {
    String mdKey = "${session.toolKeyPrefix}_${user}_${classKey}"
    return mdKey
}

// Function to get user from key
def getUserFromKey( String key ) {
    String user = key.split( "_" )[1]
    return user
}

// Function to get classifying name from key
def getClassifyingNameFromKey( String key ) {
    String classifyingName = key.split( "_" )[2]
    return classifyingName
}

// Function to show a temporary message that fades out
def showFadeOutMessage( label ) {
    label.setOpacity( 0.7 )
    label.setVisible( true )
    label.setManaged( true )
    
    def fadeOut = new FadeTransition( Duration.millis( 800 ), label )
    fadeOut.setDelay( Duration.millis( 700 ) )
    fadeOut.setToValue( 0.0 )
    fadeOut.setOnFinished {
        label.setVisible( false )
        label.setManaged( false )
    }
    fadeOut.play()
}

// Function to color annotations based on color map (or create new color)
def colorAnnosFromMap( ViewerSessionVars session, List mdKeys ) {
    session.allClassAnnos.each { anno ->
        session.currentAnnoColorsMap[ anno ] = anno.getColor()
        
        def md = anno.getMetadata()
        
        List classifierVals = mdKeys.collect { md.get( it ) }.unique().sort()
        classifierVals.replaceAll { it == null ? "unclassified" : it }
        boolean sameClass = classifierVals.size() == 1
        
        def classMatch = session.highlightStatesAndColorsMap.find { it.state == classifierVals }
        
        if (sameClass && classifierVals[0] in session.statesAndColors.state) {
            int color = session.statesAndColors.find { it.state == classifierVals[0] }.color
            anno.setColor( color )
            
            if (!classMatch) {
                session.highlightStatesAndColorsMap << [state: classifierVals, color: color, count: 1]
            } else {
                classMatch.count = (classMatch.count ?: 0) + 1
            }
        
        } else if (classMatch) {
            anno.setColor( classMatch.color )
            classMatch.count = (classMatch.count ?: 0) + 1
        
        } else {
            int newColor = generateVisibleColor(
                session.highlightStatesAndColorsMap.color,
                session.statesAndColors.color + session.channelSpecifics.color.collect { it & 0x00FFFFFF } // stay away from other class and channel colors
            )
            
            anno.setColor( newColor )
            session.highlightStatesAndColorsMap << [state: classifierVals, color: newColor, count: 1]
        }
    }
}

// Function to get bounds of a group of annos (width, height, centroidX, centroidY)
def getBounds( annoGroup, ViewerSessionVars session ) {
    double left   = session.viewer.getServerWidth()
    double right  = 0
    double top    = session.viewer.getServerHeight()
    double bottom = 0
    
    annoGroup.each {
        def r = it.getROI()
        def x = r.getBoundsX()
        def y = r.getBoundsY()
        def w = r.getBoundsWidth()
        def h = r.getBoundsHeight()
        
        left   = Math.min( left,   x )
        right  = Math.max( right,  x + w )
        top    = Math.min( top,    y )
        bottom = Math.max( bottom, y + h )
    }
    
    double width  = Math.abs( right - left )
    double height = Math.abs( bottom - top )
    double centroidX = (left + right) / 2
    double centroidY = (top + bottom) / 2
    
    return [x: centroidX, y: centroidY, w: width, h: height]
}

// Function to fire a dialog message
def conditionallyFireMessage( ViewerSessionVars session ) {   
    long now = System.currentTimeMillis()    
    boolean enoughTimePassed = (now - session.lastMessageTime) > 5000
    boolean differentMessage = session.lastMessage != session.message
    
    if (session.message && (differentMessage || enoughTimePassed)) {
        Platform.runLater { Dialogs.showWarningNotification( "", session.message ) }
        session.lastMessage = session.message
        session.lastMessageTime = now
    }
}

// Functions to switch between FX colors and Int colors (UI vs annotation objects require different color classes)
@Field def intToFx = { int rgb ->
    Color.rgb(
        (rgb >> 16) & 0xFF,
        (rgb >> 8)  & 0xFF,
        rgb & 0xFF
    )
}
@Field def fXtoRGBInt = { Color fX ->
    fX = fX ?: Color.GRAY
    ((int)(fX.red   * 255) << 16) |
    ((int)(fX.green * 255) << 8)  |
    (int)(fX.blue   * 255)
}

// Function to read in all rows of extenal project file into completeMatrix
def readJSONtoPreferencesMap( jsonFile ) {
    if (jsonFile.length() > 0L && jsonFile.getText() != "null") {
        def inputGson = new Gson()
        def readDataFile = inputGson.fromJson( jsonFile.getText(), Object )
        if (readDataFile instanceof Map) {
            return readDataFile
        }
    }
    
    return [:]
}

// Function to write preferences to .json file
def writePreferencesMaptoJSON( preferencesFile, preferencesMap ) {
    def outputGson = new GsonBuilder().setPrettyPrinting().create()
    preferencesFile.text = outputGson.toJson( preferencesMap )
}

// Function to create icon for UI
def addIcon( node, iconCode, int size ) {
    def icon = IconFactory.createNode( iconCode, size )
    node.setGraphic( icon )
}

// Function to get user preferences for run
def getCodePreferences( ViewerSessionVars session ) {
    def currentImageData = getCurrentImageData()
    def project = getProject()
    
    // Make/get prefs folder as needed
    def projectDirectory = project.getBaseDirectory()
    def projectFolderNames = projectDirectory.listFiles().collect { it.getName() }
    def scriptPrefsFolderName = "AnnoTools-Data"
    def scriptPrefsFileName = "Manually-Classify_Preferences" + ".json"
    def prefsFolder = new File( projectDirectory, scriptPrefsFolderName )
    if (!prefsFolder.exists()) {
        // Create a subfolder inside that folder
        prefsFolder.mkdirs()
    }
    session.prefsFile = new File( prefsFolder, scriptPrefsFileName )
    // Make file if does not exist
    if (!session.prefsFile.exists()) {
        session.prefsFile.createNewFile()
    }
    
    // Getting existing/previous preferences
    def prePrefsMap = readJSONtoPreferencesMap( session.prefsFile )


    // -- Open the UI with preference options --
    runOnFxThreadAndWait {
        // --- Create the dialog ---
        def preferencesDialog = new Dialog()
        preferencesDialog.setTitle( "Manual Classifications" )
        
        def exportPrefsBtnType = new ButtonType( " Export", ButtonBar.ButtonData.LEFT )
        def importPrefsBtnType = new ButtonType( " Import", ButtonBar.ButtonData.LEFT )
        
        preferencesDialog.getDialogPane().getButtonTypes().setAll( ButtonType.OK, ButtonType.CANCEL, exportPrefsBtnType, importPrefsBtnType )
        def okBtn = preferencesDialog.getDialogPane().lookupButton( ButtonType.OK )
        def cancelBtn = preferencesDialog.getDialogPane().lookupButton( ButtonType.CANCEL )
        def exportPrefsBtn = preferencesDialog.getDialogPane().lookupButton( exportPrefsBtnType )
        def importPrefsBtn = preferencesDialog.getDialogPane().lookupButton( importPrefsBtnType )
        
        addIcon( exportPrefsBtn, FontAwesome.Glyph.UPLOAD, 12 )
        addIcon( importPrefsBtn, FontAwesome.Glyph.DOWNLOAD, 12 )
        exportPrefsBtn.setFocusTraversable( false )
        importPrefsBtn.setFocusTraversable( false )
        
        ButtonBar.setButtonUniformSize( okBtn, false )
        
        setTT( exportPrefsBtn, "Export existing preferences to .json file" )
        setTT( importPrefsBtn, "Import preferences from .json file" )
        
        def root = new BorderPane()
        root.setPrefSize( 400, 350 )
        
        // define shared column constraints
        def colGrow = new ColumnConstraints()
        colGrow.setHgrow( Priority.ALWAYS )
        def colFixed = new ColumnConstraints()
        colFixed.setHgrow( Priority.NEVER )
        
        
        // --- TOP: checkbox toggling dropdown mode ---
        def classifierModeBtn = new ToggleButton( "Classifier Mode" )
        def reviewerModeBtn   = new ToggleButton( "Reviewer Mode" )
        
        // Stop mode buttons from being untoggled (must click other)
        [classifierModeBtn, reviewerModeBtn].each { btn ->
            btn.addEventFilter( MouseEvent.MOUSE_PRESSED ) {
                if (btn.isSelected()) {
                    it.consume()
                }
            }
        }
        
        def modeButtons = new ToggleGroup()
        classifierModeBtn.setToggleGroup( modeButtons )
        reviewerModeBtn.setToggleGroup( modeButtons )
        
        def selectedStyle =
            "-fx-border-color: black;" +
            "-fx-border-width: 1;" +
            "-fx-font-weight: bold;" +
            "-fx-background-color: derive(-fx-base, -30%);"
        
        def unselectedStyle =
            "-fx-opacity: 0.7;"
        
        def updateModeButtonStyles = {
            classifierModeBtn.setStyle(
                classifierModeBtn.isSelected() ? selectedStyle : unselectedStyle
            )
            reviewerModeBtn.setStyle(
                reviewerModeBtn.isSelected() ? selectedStyle : unselectedStyle
            )
        }
        
        modeButtons.selectedToggleProperty().addListener { obs, oldVal, newVal ->
            updateModeButtonStyles()
        }
        
        updateModeButtonStyles()
        
        
        def changeClassColorOrderBtn = new Button( "Classes/Colors" )
        setTT( changeClassColorOrderBtn, "Change classification specifics for both modes" )
        
        def specifyReviewAnnosBtn = new Button ( "Reviewer annos" )
        setTT( specifyReviewAnnosBtn, "Specify which annotations should be reviewed (reviewer mode only)" )
        
        // Spacer that eats all extra horizontal space
        def topRowSpacer = new Region()
        HBox.setHgrow( topRowSpacer, Priority.ALWAYS )
        
        // Make bounding box, add children, assign to UI and set spacing
        def topRow = new HBox( 10 )
        topRow.setPadding( new Insets( 8 ) )
        topRow.getChildren().setAll(
            classifierModeBtn,
            reviewerModeBtn,
            topRowSpacer,
            changeClassColorOrderBtn,
            specifyReviewAnnosBtn
        )
        topRow.getChildren().each {
            it.setPadding( new Insets( 10 ) )
        }
        
        // Widening the dialog box so that nothing is cut off
        topRow.setMinWidth( Region.USE_PREF_SIZE )
        root.top = topRow
        
        
        // --- LEFT: dropdown (user box) + text fields ---
        def userLabel = new Label( " User:" )
        addIcon( userLabel, FontAwesome.Glyph.USER, 14 )
        
        def userFieldNotice = new Label()
        userFieldNotice.setPadding( new Insets( 0, 0, 0, 10 ) )
        userFieldNotice.setVisible( false )
        userFieldNotice.setManaged( false )
        
        def userLabelRow = new HBox( 2, userLabel, userFieldNotice )   
        userLabelRow.setPadding( new Insets( 10, 0, 0, 0 ) )
        
        def users = new ComboBox()
        users.setMaxWidth( Double.MAX_VALUE )
        HBox.setHgrow( users, Priority.ALWAYS )
        setTT( [userLabel, users], classifierModeBtn.isSelected() ? "Select a classifier" : "Select a reviewer" )
        
        def userInputField = new TextField()
        userInputField.setPromptText( "Add with ENTER" )
        setTT( userInputField, (classifierModeBtn.isSelected() ? "Type a new classifier" : "Type a new reviewer") + " + press enter to add" )
        userInputField.setMaxWidth( Double.MAX_VALUE )
        HBox.setHgrow( userInputField, Priority.ALWAYS )
        
        def addUserBtn = new Button()
        setTT( addUserBtn, classifierModeBtn.isSelected() ? "Add a new classifier" : "Add a new reviewer" )
        addIcon( addUserBtn, FontAwesome.Glyph.PLUS, 12 )
        def removeUserBtn = new Button()
        setTT( removeUserBtn, "Remove a user" )
        addIcon( removeUserBtn, FontAwesome.Glyph.MINUS, 12 )
        def removeUserDropdown = new ComboBox()
        removeUserDropdown.setMaxWidth( Double.MAX_VALUE )
        HBox.setHgrow( removeUserDropdown, Priority.ALWAYS )
        
        def userRow = new HBox( 5, users, addUserBtn, removeUserBtn )
        
        // Set remove user option cells style
        removeUserDropdown.setCellFactory {
            new ListCell() {
                
                {
                    styleProperty().bind(
                        Bindings.when( hoverProperty() )
                            .then( "-fx-background-color: rgba(255,255,255,0.20);" )
                            .otherwise( "-fx-background-color: rgba(255,0,0,0.08);" )
                    )
                }
                
                @Override
                protected void updateItem(Object item, boolean empty) {
                    super.updateItem( item, empty )
        
                    if (empty || item == null) {
                        setText( null )
                        setGraphic( null )
                        return
                    }
                    
                    def nameLabel = new Label( item.toString() )
                    nameLabel.setPadding( new Insets( 0, 0, 0, 0 ) )
                    nameLabel.setStyle( "-fx-text-fill: white;" )
                    
                    def removeUserSpacer = new Region()
                    HBox.setHgrow( removeUserSpacer, Priority.ALWAYS )
                    
                    def xLabel = new Label("✖  ")
                    xLabel.setStyle( "-fx-text-fill: white;" )
                    
                    def cellBox = new HBox( nameLabel, removeUserSpacer, xLabel )
                    cellBox.setAlignment( Pos.CENTER_LEFT )
                    
                    setGraphic( cellBox )
                    setText( null )
                }
            }
        }
        
        // Set mode boolean variable and tip for add/remove user based on classifier/reviewer mode
        modeButtons.selectedToggleProperty().addListener { obs, oldToggle, newToggle ->
            session.classifierMode = (newToggle == classifierModeBtn)
            
            setTT( addUserBtn, session.classifierMode ? "Add a new classifier" : "Add a new reviewer")
            setTT( userInputField, (session.classifierMode ? "Type a new classifier" : "Type a new reviewer") + " + press enter to add" )
            setTT( removeUserBtn, session.classifierMode ? "Remove a classifier" : "Remove a reviewer")
            setTT( [userLabel, users], session.classifierMode ? "Select a classifier" : "Select a reviewer" )
        }
        
        
        
        def classifierOptions
        def reviewerOptions   
        
        boolean addedUser = false
        
        // When enter is pressed, add the new user
        userInputField.setOnAction {
            String newUser = userInputField.getText().trim()?.capitalize()
            
            if (newUser) {
                if (session.classifierMode && newUser !in classifierOptions) {
                    classifierOptions << newUser
                } else if (session.reviewerMode && newUser !in reviewerOptions) {
                    reviewerOptions << newUser
                } else {
                    userInputField.clear()
                    userFieldNotice.setStyle( "-fx-text-fill: #ff6b6b;" )
                    userFieldNotice.setText( "*User exists*" )  
                    showFadeOutMessage( userFieldNotice )
                    return
                }
                
                userFieldNotice.setStyle( "-fx-text-fill: #5cb85c;" )
                userFieldNotice.setText( "*Added ${newUser}*" )              
                showFadeOutMessage( userFieldNotice )
                
                userRow.getChildren().set( 0, users )
                
                userInputField.clear()
                addUserBtn.setDisabled( false )
                users.setValue( newUser )
                addedUser = true
            }
        }
        
        // Forcing new users to only have letters/numbers and showing warning if not
        userInputField.textProperty().addListener { obs, oldVal, newVal ->
            if (!newVal.matches( "[A-Za-z0-9]*" )) {
                userInputField.setText( oldVal )
                
                userFieldNotice.setStyle( "-fx-text-fill: #ff6b6b;" )
                userFieldNotice.setText( "*Letters/numbers*" )           
                showFadeOutMessage( userFieldNotice )
            }
        }
        
        // Set field to add a user when button clicked
        addUserBtn.setOnAction {
            addUserBtn.setDisabled( true )
            userRow.getChildren().set( 0, userInputField )
            userInputField.requestFocus()
            addedUser = false
        }
        
        // If adding a user and focus is changed, cancelling add
        preferencesDialog.getDialogPane().addEventFilter( MouseEvent.MOUSE_PRESSED ) { e ->
            if (!userInputField.isHover()) {
                if (!addedUser) {
                    addUserBtn.setDisabled( false )
                    userRow.getChildren().set( 0, users )
                }
            }
        }
        
        boolean removedUser = false
        
        // Listener for which user is clicked when removing
        def removeUserListener      
        removeUserListener = { obs, oldVal, newVal ->
            if (oldVal != newVal) {
                if (session.classifierMode) {
                    classifierOptions.remove( newVal )
                } else {
                    reviewerOptions.remove( newVal )
                }
                
                userFieldNotice.setStyle( "-fx-text-fill: #ff6b6b;" )
                userFieldNotice.setText( "*Removed ${newVal}*" )           
                showFadeOutMessage( userFieldNotice )
                removedUser = true
            }
            
            removeUserDropdown.valueProperty().removeListener( removeUserListener )
            userRow.getChildren().set( 0, users )
            users.show()
            removeUserBtn.setDisabled( false )
        }
        
        // Action when remove user button is clicked
        String prevSelectedUser
        removeUserBtn.setOnAction {
            removeUserBtn.setDisabled( true )
            removedUser = false
            
            prevSelectedUser = users.getValue()
            userRow.getChildren().set( 0, removeUserDropdown )
            
            def currentOptions = session.classifierMode ? classifierOptions : reviewerOptions
            removeUserDropdown.setItems( FXCollections.observableArrayList( currentOptions ) )
            
            removeUserDropdown.valueProperty().addListener( removeUserListener )
        
            userRow.applyCss()
            userRow.layout()
            
            removeUserDropdown.show()
        }
        
        // If removing a user and focus is changed, cancelling add
        removeUserDropdown.showingProperty().addListener { obs, wasShowing, isShowing ->
            if (wasShowing && !isShowing && !removedUser) {
                removeUserDropdown.valueProperty().removeListener( removeUserListener )
                userRow.getChildren().set( 0, users )
                removeUserBtn.setDisabled( false )
            }
        }
        
        
        // New text fields
        def annoClassLabel = new Label( " Annotation class:" )
        annoClassLabel.setPadding( new Insets( 10, 0, 0, 0 ) )
        addIcon( annoClassLabel, FontAwesome.Glyph.STAR_HALF_ALT, 14 )
        def annoClassField = new ComboBox()
        annoClassField.setMaxWidth( Double.MAX_VALUE )
        setTT( [annoClassLabel, annoClassField], "Class of annotations to be classified. \n If missing, add to QuPath class list." )
        
        def projectPathClasses = project.getPathClasses().findAll { it?.getName() }    
        def currentImageAnnos = currentImageData ? getAnnotationObjects() : []
        def countsByClass = currentImageAnnos.countBy { it.getPathClass() }
        
        // Organizing any pathclasses with objects in the dropdown to the top of the list
        def highCounts = []
        def lowCounts = []
        projectPathClasses.each { pc ->
            def entry = [name: pc.getName(), num: countsByClass[ pc ] ?: 0]
            (entry.num > 0 ? highCounts : lowCounts) << entry
        }
        
        highCounts.sort { -it.num } // Sort for descending abundance
        def pathClassEntries = highCounts + lowCounts
        
        // Set dropdown selection to show counts
        annoClassField.setCellFactory {
            new ListCell() {      
                @Override
                protected void updateItem( item, boolean empty ) {
                    super.updateItem( item, empty )
                    if (empty || item == null) {
                        setText( null )
                    } else {
                        def pc = item.name
                        def num = item.num
                        setText( num > 0 ? "${pc} [${num}]" : "${pc}" )
                    }
                }
            }
        }
        
        // Set actual pathclass name to be passed to script (no number)
        annoClassField.setConverter( new StringConverter() {     
            String toString( def item ) {
                def pc  = item.name
                def num = item.num
                
                return num > 0 ? "${pc} [${num}]" : "${pc}"
            }
            
            def fromString( String string ) {
                return null
            }
        })
        
        annoClassField.setItems( FXCollections.observableArrayList( pathClassEntries ) )
        
        
        def classifyingNameOptions
        
        def classifyingNameLabel = new Label( " Classifying name:" )
        addIcon( classifyingNameLabel, FontAwesome.Glyph.TAG, 14 )
        
        def classifyingNameNotice = new Label()
        
        classifyingNameNotice.setPadding( new Insets( 0, 0, 0, 10 ) )
        classifyingNameNotice.setVisible( false )
        classifyingNameNotice.setManaged( false )
        
        def classifyingNameLabelRow = new HBox( 2, classifyingNameLabel, classifyingNameNotice )      
        classifyingNameLabelRow.setPadding( new Insets( 10, 0, 0, 0 ) )
        
        def classifyingNameSelection = new ComboBox()
        classifyingNameSelection.setMaxWidth( Double.MAX_VALUE )
        HBox.setHgrow( classifyingNameSelection, Priority.ALWAYS )
        classifyingNameSelection.setEditable( true )
        classifyingNameSelection.setPromptText( "e.g. CAA" )
        setTT( [classifyingNameLabel, classifyingNameSelection], "Simple key for what is being classified to be stored in metadata (e.g. CAA). \nLast 5 are stored." )
        
        def populateClassifyingNamesBtn = new Button()
        setTT( populateClassifyingNamesBtn, "Populate classifying name options from existing annotations in project." )
        addIcon( populateClassifyingNamesBtn, FontAwesome.Glyph.SEARCH, 12 )
        
        def classifyingNameRow = new HBox( 5, classifyingNameSelection, populateClassifyingNamesBtn )
        
        // Forcing classifying name to only have letters/numbers and showing warning if not
        classifyingNameSelection.getEditor().textProperty().addListener { obs, oldVal, newVal ->
            if (!newVal.matches( "[A-Za-z0-9]*" )) {
                classifyingNameSelection.getEditor().setText( oldVal )
                
                classifyingNameNotice.setText( "*Letters/numbers*" )
                classifyingNameNotice.setStyle( "-fx-text-fill: #ff6b6b;" )
                showFadeOutMessage( classifyingNameNotice )
            }
        }
        
        // Running through project and populating classifying name options and sending notice
        populateClassifyingNamesBtn.setOnAction {
            def uniqueNames = project.getImageList().collectMany { entry ->
                entry.readImageData().getHierarchy().getAnnotationObjects().collectMany { anno ->
                    anno.getMetadata().keySet().findAll { it.contains( session.toolKeyPrefix ) }
                }
            }.unique().collect { getClassifyingNameFromKey(it) }.unique()
            
            classifyingNameOptions.setAll( uniqueNames )
            classifyingNameOptions = classifyingNameOptions.unique()
            classifyingNameSelection.setItems( classifyingNameOptions )
            if (!classifyingNameSelection.getItems().isEmpty()) {
                classifyingNameSelection.setValue( classifyingNameSelection.getItems().first() )
            }
            
            if ( !uniqueNames.isEmpty() ) {
                classifyingNameNotice.setText( "*${uniqueNames.size()} added*" )
                classifyingNameNotice.setStyle( "-fx-text-fill: #5cb85c;" )
            } else {
                classifyingNameNotice.setText( "*None available*" )
                classifyingNameNotice.setStyle( "-fx-text-fill: #ff6b6b;" )
            }
            
            showFadeOutMessage( classifyingNameNotice )
            populateClassifyingNamesBtn.setDisabled( true )
        }
        
        
        def gridSizeLabel = new Label( " Grid size (µm):" )
        addIcon( gridSizeLabel, FontAwesome.Glyph.TH, 14 )
        
        def gridSizeField = new TextField()
        gridSizeField.setMaxWidth( Double.MAX_VALUE )
        gridSizeField.setPromptText( "Size of grouping grid (µm)" )
        setTT( [gridSizeLabel, gridSizeField], "Size of grouping grid (µm). Increasing this reduces the number of groups per image but increases objects per group." )
        
        def gridSizeNotice = new Label( "*Numbers only*" )
        gridSizeNotice.setStyle( "-fx-text-fill: #ff6b6b;" )
        gridSizeNotice.setPadding( new Insets( 0, 0, 0, 10 ) )
        gridSizeNotice.setVisible( false )
        gridSizeNotice.setManaged( false )
        
        gridSizeRow = new HBox( 2, gridSizeLabel, gridSizeNotice )  
        gridSizeRow.setPadding( new Insets( 10, 0, 0, 0 ) )
        
        // Enforce integer-only input for grid size and show warning
        gridSizeField.textProperty().addListener { obs, oldVal, newVal ->
            if (!newVal.matches( "\\d*" )) {
                gridSizeField.setText( oldVal )
                
                showFadeOutMessage( gridSizeNotice )
            }
        }
        
        // Can't leave the field without restoring value
        String oldGridSize = gridSizeField.getText()
        gridSizeField.focusedProperty().addListener { obs, oldVal, newVal ->
            if (newVal) {
                oldGridSize = gridSizeField.getText()
            } else if (gridSizeField.getText().isEmpty()) {
                gridSizeField.setText( oldGridSize )
            }
        }
        
        // Create bounding box, add children and set onto UI
        def leftBox = new VBox( 10 )
        leftBox.setPadding( new Insets( 5 ) )
        leftBox.setPrefWidth( 265 )
        leftBox.getChildren().setAll(
            userLabelRow, userRow,
            annoClassLabel, annoClassField,
            classifyingNameLabelRow, classifyingNameRow,
            gridSizeRow, gridSizeField
        )
        root.left = leftBox
        
        
        
        // --- BOTTOM: checkbox for filled classified annos ---
        def bottomRowSpacer = new Region()
        HBox.setHgrow( bottomRowSpacer, Priority.ALWAYS )
        
        def checkingWorkCheckbox = new CheckBox( "Check work" )
        setTT( checkingWorkCheckbox, "Go over user's completed objects rather than new ones." )
        
        def bottomRow = new HBox()
        bottomRow.setPadding( new Insets( 20, 10, 0, 0 ) )
        bottomRow.getChildren().setAll( bottomRowSpacer, checkingWorkCheckbox )
        root.bottom = bottomRow
        
        // Changing applicable preferences live when switching between modes
        modeButtons.selectedToggleProperty().addListener { obs, oldToggle, newToggle ->
            boolean inClassifierMode = (newToggle == classifierModeBtn)
            
            gridSizeLabel.setVisible( inClassifierMode )
            gridSizeField.setVisible( inClassifierMode )
            specifyReviewAnnosBtn.setVisible( !inClassifierMode )
            
            def userOptions = inClassifierMode ? classifierOptions : reviewerOptions
            users.setItems( userOptions )
            
            users.getSelectionModel().selectFirst()
            
            okBtn.setText( inClassifierMode ? "Begin classifying" : "Begin reviewing" )
        }
        
        
        
        // --- CENTER: scrollable list of channels (max 3 allowed) ---
        // Create all channel checkboxes
        // Getting comomon available channels across project images
        def image1 = project.getImageList()[0]
        def image1Server = image1.readImageData().getServer()
        List image1Channels = image1Server.getMetadata().getChannels().collect { it.getName() }
        def channelCheckboxes = image1Channels.collect { channelName ->
            new CheckBox( channelName )
        }
        
        // Create bounding box for channels and relevant options
        def fullChannelsBox = new VBox( 5 )
        fullChannelsBox.setPadding( new Insets( 0, 5, 5, 5 ) )
        
        // Create bounding box for channel checkboxes
        def channelCBsBox =  new VBox( 5 )
        channelCBsBox.setPadding( new Insets( 5 ) )
        
        // Add tooltip to the channel checkboxes bounding box
        def chBoxToolTipBaseText = "Select channels that will help classify objects."
        def chBoxToolTipText = chBoxToolTipBaseText
        setTT( channelCBsBox, chBoxToolTipText )
        
        int maxChannels = 4
        
        // Items in top channel row
        def channelsLabel = new Label( "Select up to ${maxChannels} channels: [0]" )
        channelsLabel.setPadding( new Insets( 5, 0, 0, 0 ) )
        def fullChannelsBoxSpacer = new Region()
        HBox.setHgrow( fullChannelsBoxSpacer, Priority.ALWAYS )
        def clearChannelsBtn = new Button()
        setTT( clearChannelsBtn, "Clear selected channels" )
        addIcon( clearChannelsBtn, FontAwesome.Glyph.TRASH, 15 )
        
        // Assemble top row and add as child of larger channel box
        def topChannelRow = new HBox( 5 )
        topChannelRow.setPadding( new Insets( 8 ) )
        topChannelRow.getChildren().setAll( channelsLabel, fullChannelsBoxSpacer, clearChannelsBtn )
        
        // Add channel checkboxes and listeners to enforce max number
        channelCheckboxes.each { cb ->
            cb.selectedProperty().addListener { obs, oldVal, newVal ->
                def selectedCount = channelCheckboxes.count { it.isSelected() }
                channelsLabel.setText( "Select up to ${maxChannels} channels: [${selectedCount}]" )
                
                chBoxToolTipText = chBoxToolTipBaseText
                if (selectedCount > 0) {
                    def selectedNames = channelCheckboxes.findAll { it.isSelected() }.collect { it.getText() }
                    chBoxToolTipText += " \n \n" + "Channels selected:"
                    selectedNames.each { chBoxToolTipText += "\n- " + it }
                }
                
                setTT( channelCBsBox, chBoxToolTipText )
                
                if (selectedCount > maxChannels) {
                    // Too many – revert this one
                    cb.setSelected( false )
                }
            }
            channelCBsBox.getChildren().add( cb )
            cb.setFocusTraversable( false )
        }
        
        // Clear all checked channels when clear button is pressed
        clearChannelsBtn.setOnAction {
            channelCheckboxes.each { it.setSelected( false ) }
        }
        
        // Adding all the pieces together
        def channelsScroll = new ScrollPane( channelCBsBox )
        channelsScroll.setFitToWidth( true )
        fullChannelsBox.getChildren().setAll( topChannelRow, channelsScroll )
        root.right = fullChannelsBox
        
        preferencesDialog.getDialogPane().content = root
        
        // Formatting scrollable box
        fullChannelsBox.setStyle( "-fx-border-color: #000000" )
        topChannelRow.setStyle( "-fx-border-color: #000000; -fx-border-width: 0 0 1 0" )
        channelsScroll.setStyle( "-fx-background-color: transparent" )
        
        
        // --- Creating popout to change class order and assigned colors ---
        def states = ["true", "false", "maybe", "other", "unknown"]
        
        def defaultStateOrder = ["false", "true", "maybe"]
        
        def defaultReviewerStates = ["true", "false"]
        
        int minClassifierStates = 2
        int maxClassifierStates = 5
        
        int minReviewerStates = 2
        int maxReviewerStates = 3
        
        // Defaults
        def defaultConfirmedColors = [
            "false": Color.RED,
            "true" : Color.WHITE,
            "maybe": Color.GRAY
        ]
        def defaultUnconfirmedColor = Color.web( "#FF69B4" )
        
        def automaticColorOptions = [Color.RED, Color.WHITE, Color.GRAY, Color.CYAN, Color.LIME, defaultUnconfirmedColor]
        
        // Stored output used elsewhere in the script
        def stateColorMap = []
        
        // Locked in values (before pressing ok, changing in the window are temporary)
        def lockedStateOrder = defaultStateOrder
        def lockedReviewerStates = defaultReviewerStates
        def lockedConfirmedColors = [:]
        lockedStateOrder.each { stateName ->
            lockedConfirmedColors[ stateName ] = defaultConfirmedColors[ stateName ] ?: Color.GRAY
        }
        def lockedUnconfirmedColor = defaultUnconfirmedColor
        
        // Build/update stateColorMap to pass to the rest of the script
        def rebuildStateColorMap = {
            stateColorMap = []
            
            lockedStateOrder.eachWithIndex { stateName, idx ->
                def entry = [
                    state   : stateName as String,
                    color   : fXtoRGBInt( lockedConfirmedColors[ stateName ] as Color ),
                    revState: (stateName in lockedReviewerStates) as boolean
                ]
                if (idx == 0) {
                    entry.colorU = fXtoRGBInt( lockedUnconfirmedColor as Color )
                }
                stateColorMap << entry
            }
        }
        
        rebuildStateColorMap()
        
        
        // Action when the class/color selection is opened
        changeClassColorOrderBtn.setOnAction {
            def classColorDialog = new Dialog<ButtonType>()
            classColorDialog.setTitle( "Assign state order and colors" )
            
            classColorDialog.getDialogPane().getButtonTypes().setAll(
                ButtonType.OK,
                ButtonType.CANCEL
            )
            
            def okButton = classColorDialog.getDialogPane().lookupButton( ButtonType.OK )
            
            // Making items
            def classColorGrid = new GridPane()
            classColorGrid.setHgap( 10 )
            classColorGrid.setVgap( 10 )
            classColorGrid.setPadding( new Insets( 10 ) )
            
            def dragHeaderSpacer = new Region()
            dragHeaderSpacer.setPrefWidth( 5 )
            
            def comboHeaderSpacer = new Region()
            comboHeaderSpacer.setPrefWidth( 160 )
            
            def confirmedHeader = new Label( "Color (confirmed):" )
            confirmedHeader.setPadding( new Insets( 5, 0, 0, 0 ) )
            confirmedHeader.setPrefWidth( 140 )
            
            def unconfirmedHeader = new Label( "Color (unconfirmed):" )
            unconfirmedHeader.setPadding( new Insets( 5, 0, 0, 0 ) )
            unconfirmedHeader.setPrefWidth( 140 )
            
            def tempStateOrder = new ArrayList<>( lockedStateOrder )
            def tempRevStates = lockedReviewerStates
            def tempConfirmedColors = [:]
            lockedConfirmedColors.each { k, v ->
                tempConfirmedColors[ k ] = v
            }
            def tempUnconfirmedColor = lockedUnconfirmedColor
            
            def stateBoxes = tempStateOrder.collect { new ComboBox<String>() }
            def colorPickers = tempStateOrder.collect { new ColorPicker() }
            def reviewerCheckboxes = tempStateOrder.collect { new CheckBox() }
            def colorPickerU = new ColorPicker()
            colorPickerU.setValue( tempUnconfirmedColor )
            colorPickerU.setPrefWidth( 140 )
            
            int numRevCBsOn = reviewerCheckboxes.count { it.isSelected() }
            def reviewerClassHeader = new Label( "Reviewer classes (${minReviewerStates}-${maxReviewerStates}):" )
            reviewerClassHeader.setPadding( new Insets( 5, 0, 0, 0 ) )
            reviewerClassHeader.setPrefWidth( 140 )
            
            def unconfirmedTailSpacer = new Region()
            unconfirmedTailSpacer.setPrefWidth( 65 )
            HBox.setHgrow( unconfirmedTailSpacer, Priority.ALWAYS )
            
            def draggableHeaderRow = new HBox( 6 )
            draggableHeaderRow.getChildren().setAll(
                dragHeaderSpacer,
                comboHeaderSpacer,
                confirmedHeader,
                unconfirmedHeader,
                unconfirmedTailSpacer,
                reviewerClassHeader
            )
             
            
            def colLabels = new ColumnConstraints()
            colLabels.setHgrow( Priority.NEVER )
            
            def colDragArea = new ColumnConstraints()
            colDragArea.setHgrow( Priority.ALWAYS )
            colDragArea.setFillWidth( true )
            
            def colConfirmedHeader = new ColumnConstraints()
            colConfirmedHeader.setHgrow( Priority.NEVER )
            
            def colUnconfirmedHeader = new ColumnConstraints()
            colUnconfirmedHeader.setHgrow( Priority.NEVER )
            
            classColorGrid.getColumnConstraints().setAll(
                colLabels,
                colDragArea,
                colConfirmedHeader,
                colUnconfirmedHeader
            )
            
            def restoreDefaultsBtn = new Button( " Restore defaults" )
            setTT( restoreDefaultsBtn, "Restore classification order and colors to default." )
            addIcon( restoreDefaultsBtn, FontAwesome.Glyph.UNDO, 15 )
            
            def stateLabelBox = new VBox()
            stateLabelBox.setPadding( new Insets( 2, 0, 2, 0 ) )
            
            ListView<Integer> rowListView
            
            def rowItems = FXCollections.observableArrayList( (0..<tempStateOrder.size()).collect { it } )
            rowListView = new ListView<Integer>( rowItems )
            rowListView.setFixedCellSize( 34 )
            rowListView.setMaxWidth( Double.MAX_VALUE )
            rowListView.setFocusTraversable( false )
            
            classColorGrid.add( restoreDefaultsBtn, 0, 0 )
            classColorGrid.add( draggableHeaderRow, 1, 0 )
            classColorGrid.add( stateLabelBox, 0, 1 )
            classColorGrid.add( rowListView, 1, 1 )
            
            classColorDialog.getDialogPane().setContent( classColorGrid )
            
            
            def refreshStateLabelBox
            
            def updatingSelections = false
            
            // Closure: Format all state boxes and color selectors
            def formatControls = {
                updatingSelections = true
                try {
                    tempStateOrder.eachWithIndex { state, idx ->
                        
                        def sb = stateBoxes[ idx ]
                        sb.getItems().setAll( states )
                        sb.setEditable( true )
                        setTT( sb, "Select or type a state name" )
                        sb.setPromptText( "Type or select" )
                        sb.setPrefWidth( 140 )
                        
                        def cp = colorPickers[ idx ]
                        setTT( cp, "Select color for corresponding state" )
                        
                        def rCB = reviewerCheckboxes[ idx ]
                        rCB.setPadding( new Insets( 5, 0, 0, 0 ) )
                        setTT( rCB, "Should reviewer be able to set objects to this class?" )
                        
                    }
                    setTT( colorPickerU, "Select unconfirmed color for default state" )
                } finally {
                    updatingSelections = false
                }
            }
            
            // Closure: Set values to state and color selectors from local variables
            def syncControlsFromMaps = {
                updatingSelections = true
                try {
                    tempStateOrder.eachWithIndex { state, idx ->
                        
                        def sb = stateBoxes[ idx ]
                        sb.setValue( state )
                        
                        def cp = colorPickers[ idx ]
                        cp.setValue( tempConfirmedColors[ state ] )
                        
                        def rCB = reviewerCheckboxes[ idx ]
                        rCB.setSelected( state in tempRevStates )
                    }
                    
                    colorPickerU.setValue( tempUnconfirmedColor )
                } finally {
                    updatingSelections = false
                }
            }
            
            // Closure: Collect state and color values and store in local variables/maps
            def syncMapsFromControls = {
                tempStateOrder = stateBoxes.collect { it.getValue() }
                tempRevStates = []
                
                stateBoxes.eachWithIndex { cb, idx ->
                    String stateName = cb.getValue()
                    
                    if (stateName) {
                        tempConfirmedColors[ stateName ] = colorPickers[ idx ].getValue()
                        
                        if (reviewerCheckboxes[ idx ].isSelected()) {
                            tempRevStates << stateName
                        }
                    }
                }
                
                tempUnconfirmedColor = colorPickerU.getValue()
            }
            
            // Closure: Disabling "OK" button if any criteria are violated
            def updateOkButton = {
                def rowN = stateBoxes.size()
                def stateBoxValues = stateBoxes.collect { it.getValue() }
                def colorPickerValues = colorPickers.collect { it.getValue() }
                def anyEmptyValues = (stateBoxValues + colorPickerValues).any { !it }
                int numRevClasses = reviewerCheckboxes.count { it.isSelected() }
                
                okButton.setDisable( rowN < minClassifierStates || rowN > maxClassifierStates ||
                                     numRevClasses < minReviewerStates || numRevClasses > maxReviewerStates ||
                                     anyEmptyValues ||
                                     stateBoxValues.size() != stateBoxValues.unique().size() ||
                                     colorPickerValues.size() != colorPickerValues.unique().size() )
            }
            
            // Closure: Attach a listener that blocks invalid inputs and updates respective lists/maps
            def attachStateColorListener = { item ->
                
                item.valueProperty().addListener { obs, oldVal, newVal ->
                    
                    if (updatingSelections) return
                    
                    updatingSelections = true
                    try {
                        // If state box is empty, setting to last state
                        if (!newVal) {
                            item.setValue( oldVal )
                            return
                        }
                        
                        if (item instanceof ComboBox) {
                            def restOfBoxes = stateBoxes.findAll { it != item }
                            
                            // If an existing state is entered, switching the two states
                            restOfBoxes.each { box ->
                                if (box.getValue() == newVal) {
                                    box.setValue( oldVal )
                                }
                            }
                        
                        } else if (item instanceof ColorPicker) {
                            def restOfPickers = (colorPickers + colorPickerU).findAll { it != item }
                            def usedColors = restOfPickers.collect { it.getValue() }
                            
                            // Blocking new color if already being used
                            if (newVal in usedColors) {
                                item.setValue( oldVal )
                                return
                            }
                        }
                        
                        syncMapsFromControls()
                        rowListView.refresh()
                        refreshStateLabelBox()
                        updateOkButton()
                    
                    } finally {
                        updatingSelections = false
                    }
                }
            }
            
            def RevCBListener = { obs, oldVal, newVal ->  
                if (updatingSelections) return
                
                updatingSelections = true
                try {
                    // Not allowing number selected to exceed the max
                    int numSelected = reviewerCheckboxes.count { it.isSelected() }
                    if (numSelected > maxReviewerStates || numSelected < minReviewerStates) {
                        obs.bean.setSelected( oldVal )
                    }
                    
                    updateOkButton()
                    syncMapsFromControls()
                } finally {
                    updatingSelections = false
                }
            }
            
            // Attaching listeners to existing objects
            stateBoxes.each { cb ->
                attachStateColorListener( cb )
            }
            
            colorPickers.each { cp ->
                attachStateColorListener( cp )
            }
            
            attachStateColorListener( colorPickerU )
            
            reviewerCheckboxes.each { rCB ->
                rCB.selectedProperty().addListener( RevCBListener )
            }
            
            // Closure: Create a new state box, add options and listener
            def createStateBox = {
                def sb = new ComboBox<String>()
                attachStateColorListener( sb )
                return sb
            }
            
            // Closure: Create a new color picker and add listener
            def createColorPicker = {
                def cp = new ColorPicker()
                attachStateColorListener( cp )
                return cp
            }
            
            // Closure: Create a new checkbox and add listener
            def createRevCB = {
                def rCB = new CheckBox()
                rCB.selectedProperty().addListener( RevCBListener )
                return rCB
            }
            
            def refreshRowItems = {
                rowItems.setAll( (0..<tempStateOrder.size()).collect { it } )
            }
            
            // Closure: Add class/color row from pane and associated maps/lists
            def addRow = { int insertIdx ->
                if (tempStateOrder.size() >= maxClassifierStates) return
                
                syncMapsFromControls()
                
                def unusedStates = states.findAll { !(it in tempStateOrder) }
                def firstUnusedState = unusedStates ? unusedStates[ 0 ] : null
                
                tempStateOrder.add( insertIdx, firstUnusedState )
                
                stateBoxes.add( insertIdx, createStateBox() )
                colorPickers.add( insertIdx, createColorPicker() )
                reviewerCheckboxes.add( insertIdx, createRevCB() )
                
                def usedColors = colorPickers.collect { it.getValue() } + colorPickerU.getValue()
                def firstUnusedColor = automaticColorOptions.find { it !in usedColors }
                
                // Getting rid of any convoluting keys to keep map correct size
                tempConfirmedColors.keySet().retainAll( tempStateOrder )
                tempConfirmedColors[ firstUnusedState ] = defaultConfirmedColors[ firstUnusedState ] && defaultConfirmedColors[ firstUnusedState ] !in usedColors ? defaultConfirmedColors[ firstUnusedState ] : firstUnusedColor
                
                formatControls()
                syncControlsFromMaps()
                refreshRowItems()
                rowListView.refresh()
                refreshStateLabelBox()
                updateOkButton()
            }
            
            // Closure: Delete class/color row from pane and associated maps/lists
            def removeRow = { int idx ->
                if (tempStateOrder.size() <= minClassifierStates) return
                if (idx < 0 || idx >= tempStateOrder.size()) return
                
                syncMapsFromControls()
                
                tempStateOrder.remove( idx )
                stateBoxes.remove( idx )
                colorPickers.remove( idx )
                reviewerCheckboxes.remove( idx )
                
                syncControlsFromMaps()
                refreshRowItems()
                rowListView.refresh()
                refreshStateLabelBox()
                updateOkButton()
            }
            
            // Closure: Move a class/color row to a new location once dragged and dropped
            def moveRow = { int fromIdx, int toIdx ->
                if (fromIdx == toIdx || fromIdx < 0 || toIdx < 0) return
                if (fromIdx >= tempStateOrder.size() || toIdx > tempStateOrder.size()) return
                
                syncMapsFromControls()
                
                def movedState = tempStateOrder.remove( fromIdx )
                def movedPicker = colorPickers.remove( fromIdx )
                def movedBox = stateBoxes.remove( fromIdx )
                def movedReviewerCB = reviewerCheckboxes.remove( fromIdx )
                
                if (fromIdx < toIdx)
                    toIdx--
                
                tempStateOrder.add( toIdx, movedState )
                colorPickers.add( toIdx, movedPicker )
                stateBoxes.add( toIdx, movedBox )
                reviewerCheckboxes.add( toIdx, movedReviewerCB )
                
                syncControlsFromMaps()
                rowListView.refresh()
                updateOkButton()
            }
            
            
            // Closure: Clear any style changes during state cell dragging
            def clearMarker = { ListView<Integer> lv ->
                lv.lookupAll(".list-cell").each { it.setStyle( "" ) }
            }
            
            // Closure: Create style of line to be shown when dragging states
            def edgeStyle = { boolean top, boolean bottom, int w = 3 ->
                def topColor = top ? "#3b82f6" : "transparent"
                def bottomColor = bottom ? "#3b82f6" : "transparent"
                def topWidth = top ? "${w}" : "0"
                def bottomWidth = bottom ? "${w}" : "0"
                
                return "-fx-border-color: ${topColor} transparent ${bottomColor} transparent; " +
                       "-fx-border-width: ${topWidth} 0 ${bottomWidth} 0;"
            }
            
            // Closure: Show a temporary line between cells showing where the reorder will be
            def showMarker = { ListCell<Integer> rowCell, boolean dropAbove, int fromIdx, ListView<Integer> lv ->
                clearMarker( lv )
                
                int idx = rowCell.getIndex()
                int maxIdx = tempStateOrder.size() - 1
                def visibleCells = lv.lookupAll(".list-cell")
                
                // Don't highlight the boundary if that's already the row's current position
                boolean noHighlight = (dropAbove && fromIdx == idx - 1) || (!dropAbove && fromIdx == idx + 1)           
                if (noHighlight) return
                
                // Setting highlighting          
                def neighborIdx = dropAbove ? idx - 1 : idx + 1
                def neighborCell = (neighborIdx in 0..maxIdx) ? (visibleCells.find { it.getIndex() == neighborIdx }) : null
                
                int halfBorderWidth = 2
                rowCell.setStyle( edgeStyle( dropAbove, !dropAbove, neighborCell ? halfBorderWidth : halfBorderWidth * 2 ) )
                neighborCell?.setStyle( edgeStyle( !dropAbove, dropAbove, halfBorderWidth ) )
            }
            
            // Closure: Update left hand box with "State" labels when class/color list changes
            refreshStateLabelBox = {
                stateLabelBox.getChildren().clear()
                
                def rowHeight = rowListView.getFixedCellSize()
                
                // Making, formatting and placing each "state" label
                for (idx in 1..tempStateOrder.size()) {
                    def labelText = idx == 1 ? "(Default)  State 1:" : "State ${idx}:"
                    def label = new Label( labelText )
                    label.setMinHeight( rowHeight )
                    label.setPrefHeight( rowHeight )
                    label.setMaxHeight( rowHeight )
                    label.setMaxWidth( Double.MAX_VALUE )
                    label.setAlignment( Pos.CENTER_RIGHT )
                    stateLabelBox.getChildren().add( label )
                }
                
                // Making, formatting and placing the add state button
                def addBtn = new Button( " Add state" )
                addIcon( addBtn, FontAwesome.Glyph.PLUS, 12 )
                addBtn.setMaxWidth( Double.MAX_VALUE )
                addBtn.setOnAction { addRow( tempStateOrder.size() ) }
                addBtn.setDisable( tempStateOrder.size() >= maxClassifierStates )
                setTT( addBtn, "Add another state (max ${maxClassifierStates})" )
                stateLabelBox.getChildren().add( addBtn )
                
                // Resize list + label column
                def listHeight = tempStateOrder.size() * (rowHeight - 1) + 8
                rowListView.setPrefHeight( listHeight )
                stateLabelBox.setPrefHeight( listHeight )
                stateLabelBox.setMaxHeight( listHeight )
                stateLabelBox.setMinHeight( listHeight )
                
                // Making sure overall window fits UI layout
                Platform.runLater {
                    def win = classColorDialog.getDialogPane().getScene()?.getWindow()
                    if (win instanceof Stage) {
                        win.sizeToScene()
                    }
                }
            }
            
            refreshStateLabelBox()
            
            
            // Setup row list of classes/colors and dragging behavior
            rowListView.setCellFactory { lv ->
                
                // Define/build the row with class/color
                def rowCell = new ListCell<Integer>() {
                    
                    @Override
                    protected void updateItem( Integer item, boolean empty ) {
                        super.updateItem( item, empty )
                        
                        if (empty || item == null || item < 0 || item >= stateBoxes.size()) {
                            setText( null )
                            setGraphic( null )
                            setStyle( "" )
                            return
                        }
                        
                        int idx = getIndex()
                        
                        def dragHandle = new Label( "≡" )
                        dragHandle.setPadding( new Insets( 5, 0, 0, 0 ) )
                        dragHandle.setMinWidth( 5 )
                        setTT( dragHandle, "Drag to reorder states/colors" )
                        
                        def minusBtn = new Button()
                        addIcon( minusBtn, FontAwesome.Glyph.MINUS, 10 )
                        minusBtn.setMinWidth( 28 )
                        minusBtn.setPrefWidth( 28 )
                        minusBtn.setOnAction { removeRow( idx ) }
                        minusBtn.setDisable( tempStateOrder.size() <= minClassifierStates )
                        setTT( minusBtn, "Remove state (min ${minClassifierStates})" )
                        
                        def unconfirmedCell = (idx == 0) ? colorPickerU : new Region()
                        if (idx != 0) {
                            unconfirmedCell.setMinWidth( 140 )
                            unconfirmedCell.setPrefWidth( 140 )
                        }
                        
                        def spacer1 = new Region()
                        spacer1.setPrefWidth( 80 )
                        
                        
                        def row = new HBox( 10 )
                        row.setPadding( new Insets( 2, 0, 2, 0 ) )
                        row.getChildren().setAll(
                            dragHandle,
                            stateBoxes[ idx ],
                            colorPickers[ idx ],
                            unconfirmedCell,
                            minusBtn,
                            spacer1,
                            reviewerCheckboxes[ idx ]
                        )
                        
                        setText( null )
                        setGraphic( row )
                    }
                }
                
                // When a row is grabbed to drag, records the index of the row
                rowCell.setOnDragDetected { e ->
                    if (rowCell.isEmpty()) return
                    
                    def db = rowCell.startDragAndDrop( TransferMode.MOVE )
                    def cc = new ClipboardContent()
                    cc.putString( rowCell.getIndex().toString() )
                    db.setContent( cc )
                    
                    def snapshot = rowCell.snapshot( new SnapshotParameters(), null )
                    db.setDragView( snapshot, e.getX(), e.getY() )
                    
                    e.consume()
                }
                
                // When a row is dragged over another row, reporting the index of the other row
                rowCell.setOnDragOver { e ->
                    if (e.getGestureSource() != rowCell && e.getDragboard().hasString()) {
                        e.acceptTransferModes( TransferMode.MOVE )
                        
                        double y = e.getY()
                        double h = rowCell.getHeight()
                        boolean dropAbove = y < h / 2.0
                        
                        int fromIdx = Integer.parseInt( e.getDragboard().getString() )
                        showMarker( rowCell, dropAbove, fromIdx, lv )
                    }
                    e.consume()
                }
                
                // When row dragging exits pane of cells, stops highlighting
                rowCell.setOnDragExited { e ->
                    clearMarker( lv )
                    e.consume()
                }
                
                // When row dragging stops, making any needed changes
                rowCell.setOnDragDropped { e ->
                    if (!e.getDragboard().hasString()) {
                        e.setDropCompleted( false )
                        e.consume()
                        return
                    }
                    
                    int fromIdx = Integer.parseInt( e.getDragboard().getString() )
                    int thisIdx = rowCell.getIndex()
                    
                    double y = e.getY()
                    double h = rowCell.getHeight()
                    boolean dropAbove = y < h / 2.0
                    int toIdx = dropAbove ? thisIdx : thisIdx + 1
                    
                    // Moving row in reference lists/maps
                    moveRow( fromIdx, toIdx )
                    refreshRowItems()
                    
                    // Clearing highlighting hint formatting
                    clearMarker( lv )
                    e.setDropCompleted( true )
                    e.consume()
                }
                
                return rowCell
            }
            
            
            // Setup to restore default number and order of classes/colors
            restoreDefaultsBtn.setOnAction {
                tempStateOrder = new ArrayList<>( defaultStateOrder )
                
                tempConfirmedColors = [:]
                tempStateOrder.each { stateName ->
                    tempConfirmedColors[ stateName ] = defaultConfirmedColors[ stateName ] ?: Color.GRAY
                }
                
                stateBoxes = tempStateOrder.collect { createStateBox() }
                colorPickers = tempStateOrder.collect { createColorPicker() }
                
                reviewerCheckboxes.each { it.selectedProperty().removeListener( RevCBListener ) }
                reviewerCheckboxes.each { it.setSelected( false ) }
                
                tempConfirmedColors.eachWithIndex { state, color, idx ->
                    stateBoxes[ idx ].setValue( state )
                    colorPickers[ idx ].setValue( color )
                    reviewerCheckboxes[ idx ].setSelected ( state in defaultReviewerStates )
                }
                
                reviewerCheckboxes.each { it.selectedProperty().addListener( RevCBListener ) }
                
                tempUnconfirmedColor = defaultUnconfirmedColor
                colorPickerU.setValue( tempUnconfirmedColor )
                
                formatControls()
                syncControlsFromMaps()
                refreshRowItems()
                rowListView.refresh()
                refreshStateLabelBox()
                updateOkButton()
            }
            
            
            formatControls()
            syncControlsFromMaps()
            updateOkButton()
            
            
            def classColorResult = classColorDialog.showAndWait()
            
            // Only commit states/colors to map if OK is pressed in popout window
            if (classColorResult.get() == ButtonType.OK) {
                lockedStateOrder = new ArrayList<>( tempStateOrder )
                
                lockedConfirmedColors = [:]
                tempStateOrder.each { stateName ->
                    lockedConfirmedColors[ stateName ] = tempConfirmedColors[ stateName ] ?: Color.GRAY
                }
                
                lockedReviewerStates   = tempRevStates         
                lockedUnconfirmedColor = tempUnconfirmedColor          
                rebuildStateColorMap()
            }
        }
        
        
        // --- Creating popout to select specific annotations classification criteria to review ---
        session.frequencies = ["different": "with differing classifications.", 
                               "all": "classified by ALL as",
                               "any": "classified by ANY as"]
        
        int numRevRows = session.frequencies.size()
        
        def defaultReviewAnnoFreqs = [ "different", "any" ]
        def defaultReviewAnnos     = [ null, "maybe" ]
        
        // Closure: Make a simple switch
        def makeSwitch = { boolean initialValue = false, double height = 18 ->
            def switchOn = new SimpleBooleanProperty( initialValue )
            
            double width = height * 2
            def track = new Rectangle( width, height )
            track.arcWidth = height
            track.arcHeight = height
            track.fill = initialValue ? Color.web( "#4cd964" ) : Color.web( "#c7c7cc" )
            
            def switchRadius = height / 2.5
            def slider = new Circle( switchRadius )
            slider.fill = Color.WHITE
            
            def switchPane = new StackPane( track, slider )
            switchPane.setMinSize( width, height )
            switchPane.setMaxSize( width, height )
            switchPane.setPrefSize( width, height )
            switchPane.setPadding( new Insets(0) )
            
            def updateVisual = {
                boolean on = switchOn.get()
                track.fill = on ? Color.web( "#4cd964" ) : Color.web( "#c7c7cc" )
                slider.translateX = on ? (width/4) : -(width/4)
            }
            
            switchPane.setOnMouseClicked {
                switchOn.set( !switchOn.get() )
            }
            
            switchOn.addListener { obs, oldV, newV ->
                updateVisual()
            }
            
            updateVisual()
            
            return [node: switchPane, status: switchOn]
        }
        
        def frequencySelectors = []
        def stateSelectors = []
        def switches = []
        (1..numRevRows).each {
            def fS = new ComboBox<String>()
            frequencySelectors << fS
            
            def sS = new ComboBox<String>()
            stateSelectors << sS
            
            def newSwitch = makeSwitch( true )
            switches << newSwitch
        }
        
        specifyReviewAnnosBtn.setOnAction {
            def specifyAnnosDialog = new Dialog<ButtonType>()
            specifyAnnosDialog.setTitle( "Set annotations to review" )
            
            specifyAnnosDialog.getDialogPane().getButtonTypes().setAll(
                ButtonType.OK,
                ButtonType.CANCEL
            )
            
            def okButton = specifyAnnosDialog.getDialogPane().lookupButton( ButtonType.OK )
            
            def restoreReviewerAnnoDefaultsBtn = new Button( " Restore defaults" )
            setTT( restoreReviewerAnnoDefaultsBtn, "Restore reviewer criteria to default." )
            addIcon( restoreReviewerAnnoDefaultsBtn, FontAwesome.Glyph.UNDO, 15 )
            
            setTT( switches.node, "Add more criteria for annotations to review" )
            
            def frequencySelectorLabel = new Label( "Frequency:" )
            frequencySelectorLabel.setPadding( new Insets( 5 ) )  
            frequencySelectors.each { it.getItems().setAll( session.frequencies.values() ) }
            frequencySelectors.each { it.setValue( it.getUserData() ) }
            String freqSelectorTTtext = "Select the frequency of an annotation being classified a specific state."
            setTT( [frequencySelectorLabel] + frequencySelectors, freqSelectorTTtext )
            
            def stateSelectorLabel = new Label( "State:" )
            stateSelectorLabel.setPadding( new Insets( 5 ) ) 
            stateSelectors.each { it.getItems().setAll( states ) }
            stateSelectors.each { it.setEditable( true ) }
            stateSelectors.each { it.setPromptText( "Type or select" ) }
            stateSelectors.each { it.setValue( it.getUserData() ) }
            setTT( [stateSelectorLabel] + stateSelectors, "State classifications should have (select or type)" )
            
            def leftSentences = []
            def andLabels = []
            (1..numRevRows).each {
                def lS = new Label( "Review annotations" )
                lS.setPadding( new Insets( 5 ) )
                leftSentences << lS
                
                def aL = new Label( "AND" )
                aL.setPadding( new Insets( 5 ) )
                andLabels << aL
            }
            
            // Closure: Make sure state cells are formatted correctly (disabled and "NA")
            def updateReviewerAnnoRows = {
                stateSelectors.eachWithIndex { stateSelector, i ->
                    if (frequencySelectors[ i ].getValue() == session.frequencies[ "different" ]) {
                        stateSelector.setVisible( false )
                    }
                }
            }        
            updateReviewerAnnoRows()
            
            // Stopping states from being confirmed blank
            stateSelectors.eachWithIndex { stateSelector, i ->            
                stateSelector.valueProperty().addListener { obs, oldVal, newVal ->
                    stateSelector.setValue( newVal ?: oldVal )
                }
            }
            
            // Hiding state if the frequency selection is for different classifications
            frequencySelectors.eachWithIndex { freqSelector, i ->
                freqSelector.valueProperty().addListener { obs, oldVal, newVal ->
                    stateSelectors[ i ].setVisible( newVal != session.frequencies[ "different" ] )
                }
            }
            
            
            def specifyAnnosGrid = new GridPane()
            specifyAnnosGrid.add( restoreReviewerAnnoDefaultsBtn, 0, 0 )
            def rowContainers = [:]
            
            
            for (i in 0..numRevRows-1) {
                
                def rowGrid = new GridPane()
                rowGrid.setHgap( 10 )
                rowGrid.setVgap( 0 )
                
                def toggleRowItems = []
                
                rowGrid.add( switches[ i ].node,      0, 1 )
                rowGrid.add( andLabels[ i ],          1, 0 )
                rowGrid.add( leftSentences[ i ],      1, 1 )
                rowGrid.add( frequencySelectors[ i ], 2, 1 )
                rowGrid.add( stateSelectors[ i ],     3, 1 )
                
                toggleRowItems << andLabels[ i ]
                toggleRowItems << leftSentences[ i ]
                toggleRowItems << frequencySelectors[ i ]
                toggleRowItems << stateSelectors[ i ]
                
                
                if (i == 0) {              
                    rowGrid.add( frequencySelectorLabel, 2, 0 )
                    rowGrid.add( stateSelectorLabel,     3, 0 )
                    
                    andLabels[ i ].setVisible( false )
                    switches[ i ].node.setVisible( false )
                
                } else {
                    rowContainers[ switches[ i ].node ] = toggleRowItems
                }
                
                specifyAnnosGrid.add( rowGrid, 0, 2*i + 1, 4, 2 )
            }
            
            specifyAnnosDialog.getDialogPane().setContent( specifyAnnosGrid )
            
            // Set initial switches based on previous settings
            switches.each { rowSwitch ->
                boolean isOn = rowSwitch.node.getUserData()
                rowSwitch.status.setValue( isOn )
                rowContainers[ rowSwitch.node ].each { it.setDisable( !isOn ) }
            }
            
            // Disabling row and subsequent switches/rows if turned off using switch
            switches.eachWithIndex { rowSwitch, i ->
                rowSwitch.status.addListener { boolProp, oldVal, newVal ->
                    
                    rowContainers[ rowSwitch.node ].each { it.setDisable( !newVal ) }
                    
                    def nextSwitch = (i < switches.size() - 1) ? switches[ i+1 ] : null
                    nextSwitch?.status?.setValue( false )
                    nextSwitch?.node?.setDisable( !newVal )
                }
            }
            
            // Making sure there are no empty state names
            stateSelectors.each {
                okButton.disableProperty().bind(
                    it.getEditor().textProperty().isEmpty()
                )
            }
            
            // When the restore defaults button is pressed
            restoreReviewerAnnoDefaultsBtn.setOnAction {     
                
                def revStateFreqs = defaultReviewAnnoFreqs
                def revStates     = defaultReviewAnnos 
                
                for (i in 0..numRevRows-1) {
                    
                    def freqKey = revStateFreqs[ i ]
                    def freqValue = session.frequencies[ freqKey ]
                    def state = revStates[ i ]
                    
                    if (freqValue) {
                        frequencySelectors[ i ].setValue( freqValue )
                        stateSelectors[ i ].setValue( state ?: states[ 0 ] )
                    }
                    
                    switches[ i ].status.setValue( freqValue ? true : false )
                }
                
                updateReviewerAnnoRows()
            }
            
            // Blocking nodes from becoming highlighted when clicked
            disableNonEditableFocusTraversal( specifyAnnosDialog.getDialogPane() )
            
            def specifyAnnosResult = specifyAnnosDialog.showAndWait()
            
            // Only commit states/colors to map if OK is pressed in popout window
            if (specifyAnnosResult.get() == ButtonType.OK) {
                
                frequencySelectors.each { it.setUserData( it.getValue() ) }
                stateSelectors.each     { it.setUserData( it.getValue() ) }
                switches.each           { it.node.setUserData( it.status.getValue() ) } 
            }
        }
        
        
        // -- Defining closure that sets preferences based on map --
        def setPreferencesFromMap = { pMap ->
            def nonUpdatedPrefs = []
            
            def updatePrefsOrLog = { check, primaryAction, fallbackAction, String prefName ->
                check ? primaryAction() : fallbackAction()
                if (!check) { nonUpdatedPrefs << prefName }
            }
            
            boolean classifierMode = pMap.mode != ActionStrings.REVIEWER_MODE // default = classifier
            
            updatePrefsOrLog(
                pMap.mode in [ActionStrings.CLASSIFIER_MODE, ActionStrings.REVIEWER_MODE],     
                { classifierMode ? classifierModeBtn.setSelected( true ) : reviewerModeBtn.setSelected( true ) },
                { classifierModeBtn.setSelected( true ) },
                "Mode"
            )
            
            updatePrefsOrLog(
                pMap.classifiers instanceof List && pMap.classifiers.every { it instanceof String },
                { classifierOptions = FXCollections.observableArrayList( pMap.classifiers ) },
                { classifierOptions = classifierOptions ?: FXCollections.observableArrayList() },
                "Classifier options"
            )
            
            updatePrefsOrLog(
                pMap.reviewers instanceof List && pMap.reviewers.every { it instanceof String },
                { reviewerOptions = FXCollections.observableArrayList( pMap.reviewers ) },
                { reviewerOptions = reviewerOptions ?: FXCollections.observableArrayList() },
                "Reviewer options"
            )
            
            def userOptions = classifierMode ? classifierOptions : reviewerOptions
            
            updatePrefsOrLog(
                pMap.userName instanceof String,
                { pMap.userName in userOptions ?: userOptions.addFirst( pMap.userName );
                  users.setValue( pMap.userName ) },
                { users.setValue( users.getItems()?.first() ) },
                "Username"
            )
            
            users.setItems( classifierMode ? classifierOptions : reviewerOptions )
            
            updatePrefsOrLog(
                annoClassField.getItems().find { it.name == pMap.annoClass },
                { annoClassField.setValue( annoClassField.getItems().find { it.name == pMap.annoClass } ) },
                { annoClassField.setValue( annoClassField.getItems()[0] ) },
                "Annotation class"
            )
            
            updatePrefsOrLog(
                pMap.classifyingName instanceof String,
                { classifyingNameSelection.setValue( pMap.classifyingName.toString() ) },
                { classifyingNameSelection.setValue( "" ) },
                "Classifying name"
            )
            
            updatePrefsOrLog(
                pMap.recentClassifyingNames instanceof List && pMap.recentClassifyingNames.every { it instanceof String },
                { classifyingNameOptions = FXCollections.observableArrayList( pMap.recentClassifyingNames?.unique() );
                  classifyingNameSelection.setItems( classifyingNameOptions ) },
                { classifyingNameOptions = classifyingNameOptions ?: FXCollections.observableArrayList() },
                "Classifying name options"
            )
            
            updatePrefsOrLog(
                pMap.gridSize instanceof Number,
                { gridSizeField.setText( pMap.gridSize.intValue().toString() ) },
                { gridSizeField.setText( "500" ) },
                "Grid size"
            )
            
            updatePrefsOrLog(
                pMap.selectedChannels instanceof List && channelCheckboxes.collect { it.getText() }.containsAll( pMap.selectedChannels ),
                { channelCheckboxes.each { it.setSelected( false ) }; 
                  channelCheckboxes.each { it.setSelected( it.getText() in pMap.selectedChannels ) } },
                { channelCheckboxes.each { it.setSelected( false ) } },
                "Selected channels"
            )
            
            
            // Setting reviewer annotation selections
            def revStateFreqs = pMap.reviewStateFreqs instanceof List ? pMap.reviewStateFreqs : defaultReviewAnnoFreqs
            def revStates     = pMap.reviewStates instanceof List     ? pMap.reviewStates     : defaultReviewAnnos
            
            for (i in 0..numRevRows-1) {
                def freqKey = revStateFreqs[ i ]
                def freqValue = session.frequencies[ freqKey ]
                def state = revStates[ i ]
                
                frequencySelectors[ i ].setUserData( freqValue ?: session.frequencies.values()[ 0 ] )
                stateSelectors[ i ].setUserData( freqValue ? state ?: states[ 0 ] : states[ 0 ] )
                switches[ i ].node.setUserData( freqValue ? true : false )
            }
            
            updatePrefsOrLog(
                pMap.reviewStateFreqs instanceof List && pMap.reviewStates instanceof List,
                { null },
                { null },
                "Reviewer annotation preferences"
            )
            
            // Setting classification states, order, and colors
            def savedStates = pMap.statesAndColors?.state
            lockedStateOrder = savedStates ? savedStates.unique() : defaultStateOrder
            
            def missingStates = lockedStateOrder.findAll { !(it in states) }
            if (missingStates) {
                states.addAll( missingStates )
            }
            
            lockedConfirmedColors = [:]
            lockedStateOrder.eachWithIndex {stateName, idx ->
                def savedColor = pMap.statesAndColors?[ idx ]?.color
                lockedConfirmedColors[ stateName ] =
                    savedColor instanceof Number ?
                        intToFx( savedColor as int ) :
                        (defaultConfirmedColors[ stateName ] ?: Color.GRAY)
            }
            
            def savedUnconfirmed = pMap.statesAndColors?[0]?.colorU
            lockedUnconfirmedColor = savedUnconfirmed instanceof Number ? intToFx( savedUnconfirmed as int ) : defaultUnconfirmedColor
            
            if (!savedStates || !savedUnconfirmed instanceof Number ) {
                nonUpdatedPrefs << "Classification states/order/colors"
            }
            
            // Setting reviewer-available states
            def savedReviewerStates = pMap.statesAndColors?.findAll { it.revState }?.state
            lockedReviewerStates = savedReviewerStates ? savedReviewerStates.unique() : defaultReviewerStates
            
            if (!savedReviewerStates ) {
                nonUpdatedPrefs << "Reviewer classification states"
            }
            
            
            rebuildStateColorMap()
            
            if (pMap.size() > 1L) {
                if (nonUpdatedPrefs.isEmpty()) {
                    Dialogs.showInfoNotification( "", "Successfully updated preferences." )
                
                } else {
                    String notification = nonUpdatedPrefs.collect { "  - $it" }.join( "\n" )
                    Dialogs.showWarningNotification( "", "UNABLE TO UPDATE: \n${notification}" )
                }
            }
        }
        
        setPreferencesFromMap( prePrefsMap )
        
        
        
        // Closure to collect current preferences and make map
        def collectCurrentPrefs = {
            def userName          = users.getValue().capitalize()
            def mode              = session.classifierMode ? ActionStrings.CLASSIFIER_MODE : ActionStrings.REVIEWER_MODE
            def checkingWork      = checkingWorkCheckbox.isSelected()
            def selectedChannels  = channelCheckboxes.findAll { it.isSelected() }
                                        .collect { it.getText() }
            
            def annoClass               = annoClassField.getValue().name
            def classifyingName         = classifyingNameSelection.getValue()?.trim()
            List recentClassifyingNames = classifyingNameOptions = ([classifyingName] + classifyingNameOptions).unique().take( 5 )
            def gridSizeText            = gridSizeField.getText().trim()
            def gridSize                = Integer.parseInt( gridSizeText )
            boolean instructions        = prePrefsMap.instructions
            
            // Reviewer annotation preferences
            def reviewStateFreqs = []
            def reviewStates = []
            
            for (i in 0..numRevRows-1) {         
                if (switches[ i ].node.getUserData()) {
                    reviewStateFreqs << session.frequencies.find { it.getValue() == frequencySelectors[ i ].getUserData() }.getKey()
                    reviewStates << stateSelectors[ i ].getUserData() ?: states[ 0 ]
                }
            }
            
            def prefMap = [
                "userName"               : userName,
                "classifiers"            : classifierOptions,
                "reviewers"              : reviewerOptions,
                "mode"                   : mode,
                "checkingWork"           : checkingWork,
                "selectedChannels"       : selectedChannels,
                "annoClass"              : annoClass,
                "classifyingName"        : classifyingName,
                "recentClassifyingNames" : classifyingNameOptions,
                "gridSize"               : gridSize,
                "statesAndColors"        : stateColorMap,
                "reviewStateFreqs"       : reviewStateFreqs,
                "reviewStates"           : reviewStates,
                "instructions"           : instructions
            ]
            
            return prefMap
        }
        
        // Exporting current preferences to file
        exportPrefsBtn.addEventFilter( ActionEvent.ACTION ) { e ->
            e.consume() // prevent dialog closing
            
            def fileChooser = new FileChooser()
            fileChooser.setTitle( "Export preferences file" )
            fileChooser.setInitialDirectory( projectDirectory )
            fileChooser.setInitialFileName( "classify-preferences.json" )
            fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter( "JSON files", "*.json" )
            )
            
            def owner  = preferencesDialog.getDialogPane().getScene()?.getWindow()
            def newPrefsFile = fileChooser.showSaveDialog( owner )
            
            if (newPrefsFile != null) {
                def choiceFileName = newPrefsFile.getName()
                
                def activePreferences = collectCurrentPrefs()
                writePreferencesMaptoJSON( newPrefsFile, activePreferences )
                
                Dialogs.showInfoNotification( "", "Preferences saved to \"${choiceFileName}\"" )
                preferencesDialog.getDialogPane().getScene().getWindow().toFront()
            }
        }
        
        // Importing saved preferences file
        importPrefsBtn.addEventFilter( ActionEvent.ACTION ) { e ->
            e.consume() // prevent dialog closing
            
            def fileChooser = new FileChooser()
            fileChooser.setTitle( "Select file to import preferences from:" )
            fileChooser.setInitialDirectory( projectDirectory )
            fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter( "JSON Files", "*.json" )
            )
            def owner  = preferencesDialog.getDialogPane().getScene()?.getWindow()
            def fileChoice = fileChooser.showOpenDialog( owner )
            
            if (fileChoice != null) {
                def newPreferencesMap = readJSONtoPreferencesMap( fileChoice )
                setPreferencesFromMap( newPreferencesMap )           
                
                preferencesDialog.getDialogPane().getScene().getWindow().toFront()
            }
        }
        
        
        // - Setting buttons to not be highlighted when clicked -
        disableNonEditableFocusTraversal( preferencesDialog.getDialogPane() )
        
        
        // - Disabling OK button unless certain criteria is met -
        def anySelectedChannel = Bindings.createBooleanBinding(
            { channelCheckboxes.any { it.isSelected() } },
            *channelCheckboxes.collect { it.selectedProperty() }
        )
        okBtn.disableProperty().bind(
            classifyingNameSelection.getEditor().textProperty().isEmpty()
            .or( users.valueProperty().isNull() )
            .or( gridSizeField.textProperty().isEmpty() )
            .or( anySelectedChannel.not() )
        )
        
        // Making sure to bring the selection back to the ok button after re-enable first time
        long scriptStart = System.currentTimeMillis()
        boolean firstPass = true
        
        okBtn.disableProperty().addListener { obs, wasDisabled, isNowDisabled ->
            
            boolean withinStartupWindow = (System.currentTimeMillis() - scriptStart) < 100
            
            if (!isNowDisabled && firstPass && withinStartupWindow) {
                okBtn.requestFocus()
                firstPass = false
            }
        }
        
        
        // -- Waiting for output and passing preferences to rest of code --
        def dlgResult = preferencesDialog.showAndWait()        
        return dlgResult.get() == ButtonType.OK ? collectCurrentPrefs() : null
    }
}


// === GETTING AND CHECKING USER PREFERENCES ===
Map codePreferences

preferencesLoop:
while (true) {
    // Collect user preferences
    codePreferences = getCodePreferences( session )
    
    // Writing full project matrix + image matrix to external file to "remember" prefs in project
    if (codePreferences != null) {    
        writePreferencesMaptoJSON( session.prefsFile, codePreferences )
        
    } else return // If cancelling
    
    
    // --- Storing selected preferences for use in script ---
    session.userName            = codePreferences.userName
    session.classifiers         = codePreferences.classifiers
    session.reviewers           = codePreferences.reviewers
    session.classifyingName     = codePreferences.classifyingName // Name of disease/marker being classified (for metadata)
    session.userKey             = generateMdKey( session, session.userName, session.classifyingName )
    session.classifierKeys      = session.classifiers.collect { generateMdKey( session, it, session.classifyingName ) } 
    session.checkingWork        = codePreferences.checkingWork // Set if the user is going back over their completed classifications
    session.toggleChannels      = codePreferences.selectedChannels // Channels to toggle for analysis (keep number of channels low)
    session.annoClass           = codePreferences.annoClass // Class of annotations to review
    session.gridSize            = codePreferences.gridSize // µm bins size for group review (will automatically add grid)
    session.statesAndColors     = codePreferences.statesAndColors // Map with the order of classes (to change) and respective colors
    session.reviewStateFreqs    = codePreferences.reviewStateFreqs // Frequencies of states to review
    session.reviewStates        = codePreferences.reviewStates // Which states to review
    session.instructions        = codePreferences.instructions // Whether instructions should be (or start) open/closed
    
    // --- Defining other needed variables ---
    session.freeViewModes     = [ActionStrings.ZOOM, ActionStrings.BRIGHTNESS]
    session.freeView          = false
    session.freeViewMode      = session.freeViewModes[0]
    session.reviewerStates    = session.statesAndColors.findAll { it.revState }.state
    session.showing           = null
    session.prevSelectedAnnos = []
    session.lastMessage       = ""
    session.lastMessageTime   = 0
    
    Dialogs.showInfoNotification( "", "User: ${session.userName}" )
    
    
    // --- Generating image list for auto switching ---
    def random = new Random()
    List imageList = project.getImageList()
    List randomImages = imageList.shuffled( random )
    def currentEntry = currentImageData ? project.getEntry( currentImageData ) : null
    List imageOrder = session.checkingWork ? imageList : randomImages // If classifying: random order of images. If checking work: normal order to not lose place.
    
    
    // --- Collecting reviewer criteria ---
    session.reviewerCriteria = [all: [], any: [], different: false]
    session.reviewStateFreqs.eachWithIndex { freq, i ->
        switch (freq) {
            case ("all")      : session.reviewerCriteria.all << session.reviewStates[i]; break
            case ("any")      : session.reviewerCriteria.any  << session.reviewStates[i]; break
            case ("different"): session.reviewerCriteria.different = true               ; break
        }
    }
    
    
    // --- Checking image viability ---
    def notFullyClassifiedImages = []
    def fullyClassifiedImages = []
    def userIncompleteImages = []
    def userCompleteImages = []
    def imagesWithoutAnnos = []
    def imagesWithNoReviewableAnnos = []  
    def usersWithIncompleteClassifications = [] as LinkedHashSet
    def incompleteUsersByImage = [:].withDefault { [] as LinkedHashSet }
    
    for (image in imageOrder) {
        
        def imageAnnos = image.readImageData()
            .getHierarchy()
            .getAnnotationObjects()
            .findAll { it.getPathClass()?.getName() == session.annoClass }
        
        if (imageAnnos.isEmpty()) {
            imagesWithoutAnnos << image
            continue
        }
        
        // Collect which users are missing classifications in reviewer mode
        def annosMissingClassifierKeys = session.reviewerMode ? imageAnnos.findAll { anno ->
            session.classifierKeys.any { key -> !anno.getMetadata().containsKey( key ) }
        } : []
        
        annosMissingClassifierKeys.each { anno ->
            def missingUsers = session.classifierKeys.findAll { key ->
                !anno.getMetadata().containsKey( key )
            
            }.collect { getUserFromKey( it ) }
            
            usersWithIncompleteClassifications.addAll( missingUsers )
            incompleteUsersByImage[ image.getImageName() ].addAll( missingUsers )
        }
        
        boolean anyMissingClassifierKeys = session.reviewerMode && !annosMissingClassifierKeys.isEmpty()
        
        boolean anyMissingUserKey = imageAnnos.any { anno ->
            if (session.reviewerMode) {
                matchesMdClassification(
                    anno,
                    session.reviewerCriteria.all,
                    session.reviewerCriteria.any,
                    session.reviewerCriteria.different,
                    session
                ) && !containsUserKey( anno, session )
            } else {
                !containsUserKey( anno, session )
            }
        }
        
        boolean anyUserKey = imageAnnos.any { containsUserKey( it, session ) }
        
        boolean anyAnnosToCheck = session.reviewerMode ?
            findAnnosByMdClassification(
                imageAnnos,
                all     = session.reviewerCriteria.all,
                any     = session.reviewerCriteria.any,
                diff    = session.reviewerCriteria.different,
                session = session
            ) : true
            
        if (session.reviewerMode) {
            (anyMissingClassifierKeys ? notFullyClassifiedImages : fullyClassifiedImages) << image
        }
        
        if (anyAnnosToCheck) {
            if (anyMissingUserKey) {
                userIncompleteImages << image
            } else {
                userCompleteImages << [
                    image     : image,
                    userInput : anyUserKey
                ]
            }
        } else {
            imagesWithNoReviewableAnnos << image
        }
    }
    
    // --- Collecting images to process ---
    session.imagesToProcess = session.checkingWork ? new ArrayList( userCompleteImages.image ) : new ArrayList( userIncompleteImages )
    
    // Setting current image first if needs classification
    if (currentEntry && !session.imagesToProcess.isEmpty()) {
        if (currentEntry in session.imagesToProcess) {
            session.imagesToProcess.remove( currentEntry )
        }
        session.imagesToProcess.addFirst( currentEntry )
    }
    
    // Warning if some images haven't been fully classified and allowing exit
    List warningList = []
    def addWarningCheck = { List criteria, String warning, String group, List imagesList = null ->
        if (criteria.every { it }) {
            warningList << [message: warning, group: group, images: imagesList]
        }
    }
    
    // Create a small list with readable reviewer criteria for dropdown
    List reviewerCriteriaList = ["Reviewer criteria:"] + session.reviewerCriteria.findResults { key, value ->
        if (value) {
            key != "different"
                ? "Annotations ${session.frequencies[ key ]} ${value}"
                : "Annotations ${session.frequencies[ key ]}"
        }
    }
    
    List generalCriteria = ["User: ${session.userName}", 
                            "Object class: ${session.annoClass}", 
                            "Classifying name: ${session.classifyingName}"]
    
    // - Creating specific checks + warnings -
    // User completion warnings
    addWarningCheck( [session.reviewerMode, session.checkingWork, userCompleteImages.isEmpty(), !imagesWithNoReviewableAnnos.isEmpty()],
        "No images with annos meeting reviewer criteria have been completed by user.",
        "User Completion", reviewerCriteriaList )
    addWarningCheck( [session.classifierMode, session.checkingWork, userCompleteImages.isEmpty()],
        "User has not completed any images and cannot check work.",
        "User Completion" )
    addWarningCheck( [session.checkingWork, (userIncompleteImages.size() !in [0, imageList.size()])],
        "${userIncompleteImages.size()} image(s) have not been completed by user and cannot be checked.",
        "User Completion", userIncompleteImages )
    addWarningCheck( [session.classifierMode, !session.checkingWork, userIncompleteImages.isEmpty()],
        "User has classified all viable images.",
        "User Completion", generalCriteria )
    addWarningCheck( [session.reviewerMode, session.classifierMode, !session.checkingWork, userIncompleteImages.isEmpty()],
        "User has reviewed all viable images.",
        "User Completion", reviewerCriteriaList )
    
    // Classification & Review warnings
    addWarningCheck( [session.reviewerMode, !session.checkingWork, (notFullyClassifiedImages.size() == imageList.size())],
        "No images have complete classifications to review from ${usersWithIncompleteClassifications}.",
        "Classification & Review", generalCriteria )
    addWarningCheck( [session.reviewerMode, !session.checkingWork, (notFullyClassifiedImages.size() !in [0, imageList.size()])],
        "${notFullyClassifiedImages.size()} image(s) have incomplete classifications to review from ${usersWithIncompleteClassifications}.",
        "Classification & Review", notFullyClassifiedImages )
    
    // Annotation issues warnings
    addWarningCheck( [!imagesWithoutAnnos.isEmpty()],
        "${imagesWithoutAnnos.size()} image(s) have no \"${session.annoClass}\" annotations.",
        "Annotation Issues", imagesWithoutAnnos )
    
    
    // Sending warning if criteria are met
    if (!warningList.isEmpty()) {
        
        def exitBtnType      = new ButtonType( "Exit", ButtonBar.ButtonData.CANCEL_CLOSE )
        def continueBtnType  = new ButtonType( "Continue", ButtonBar.ButtonData.OK_DONE )
        def checkWorkBtnType = new ButtonType( "Review completed annos", ButtonBar.ButtonData.OK_DONE )
        def backBtnType      = new ButtonType( "Back", ButtonData.BACK_PREVIOUS )
        
        def warningDecision = runOnFxThreadAndWait {
            
            def unfinishedAlert = new Alert( AlertType.CONFIRMATION )
            unfinishedAlert.setTitle( "WARNING: Issue(s) with images" )
            unfinishedAlert.getDialogPane().setPrefHeight( warningList.size() * 60 )
            
            if (session.imagesToProcess.isEmpty()) {
                if (userCompleteImages.userInput.contains( true )) {
                    session.imagesToProcess = new ArrayList( userCompleteImages.image )
                    session.checkingWork = true
                    unfinishedAlert.getButtonTypes().setAll( backBtnType, checkWorkBtnType, exitBtnType )
                    def checkWorkBtn = unfinishedAlert.getDialogPane().lookupButton( checkWorkBtnType )
                    checkWorkBtn.setDefaultButton( false )
                    ButtonBar.setButtonUniformSize( checkWorkBtn, false )      
                } else {
                    unfinishedAlert.getButtonTypes().setAll( backBtnType, exitBtnType )
                }
            } else {
                unfinishedAlert.getButtonTypes().setAll( backBtnType, exitBtnType, continueBtnType )
                unfinishedAlert.getDialogPane().lookupButton( continueBtnType ).setDefaultButton( false )
                unfinishedAlert.setContentText( "Continue anyway?" )
            }
            
            unfinishedAlert.getDialogPane().lookupButton( exitBtnType ).setDefaultButton( true )
            
            // Build grouped warning UI
            def warningLabelBox = new VBox( 8 )
            
            // Group warnings by their group label, preserving insertion order
            Map grouped = warningList.groupBy { it.group }
            
            grouped.each { groupName, items ->
                
                // Group header label
                def groupLabel = new Label( groupName )
                groupLabel.setStyle( "-fx-font-weight: bold; -fx-text-fill: #555555; -fx-padding: 10 0 2 10;" )
                warningLabelBox.getChildren().add( groupLabel )
                
                // Warning items within the group
                items.each { item ->
                    List problemImageList = item.images ?: []
                    def rowCount = Math.min( 3, Math.max( 1, problemImageList.size() ) )
                    
                    if (problemImageList.isEmpty()) {
                        def tp = new TitledPane( item.message, null )
                        tp.setExpanded( false )
                        tp.setCollapsible( false )
                        warningLabelBox.getChildren().add( tp )
                    } else {
                        def imageText = new TextArea( problemImageList.join( "\n" ) )
                        imageText.setEditable( false )
                        imageText.setWrapText( true )
                        imageText.setPrefRowCount( Math.max( 1, problemImageList.size() ) )
                        imageText.setMaxWidth( Double.MAX_VALUE )
                        
                        def scroll = new ScrollPane( imageText )
                        scroll.setFitToWidth( true )
                        scroll.setPrefViewportHeight( Math.min( 3, Math.max( 1, problemImageList.size() ) ) * 15 )
    
                        def tp = new TitledPane( item.message, scroll )
                        tp.setExpanded( false )
                        warningLabelBox.getChildren().add( tp )
                    }
                }
            }
    
            unfinishedAlert.getDialogPane().setHeader( warningLabelBox )
    
            return unfinishedAlert.showAndWait().orElse( exitBtnType )
        }
        
        switch (warningDecision) {
            case (continueBtnType)  : break preferencesLoop
            case (checkWorkBtnType) : break preferencesLoop
            case (backBtnType):       continue
            default:                  return
        }
    }
    
    // If no warnings or continued, moving into execution
    break preferencesLoop
}



// Collect preset options to restore after script
session.viewer = getCurrentViewer()
session.view = session.viewer.getView()
session.overlayOptions = session.viewer.getOverlayOptions()
boolean wasGridOn = session.overlayOptions.getShowGrid()
double gridX = PathPrefs.gridSpacingX.get()
double gridY = PathPrefs.gridSpacingY.get()
changeGrid( true, session.gridSize, session.gridSize, session )
session.oldAnnoThickness = PathPrefs.annotationStrokeThicknessProperty().get()

// Adding grid / changing to needed viewer settings
Platform.runLater {
    PathPrefs.annotationStrokeThicknessProperty().set( 1 )
    session.overlayOptions.setShowDetections( true )
    session.overlayOptions.setFillDetections( true )
    session.overlayOptions.setShowAnnotations( true )
    session.overlayOptions.setFillAnnotations( false )
    session.overlayOptions.setOpacity( 1 )
}


// =====================

// ===== EXECUTION =====
def queue = new LinkedBlockingQueue<String>()
// -- Loop to review image (can repeat on another image) --
imageLoop:
for (int imageIndex = 0; imageIndex < session.imagesToProcess.size(); imageIndex++) {
    
    def currentImage = session.imagesToProcess[ imageIndex ]
    def imageData = getNextImage( currentImage )
    
    // Collect (new) image important data
    def server = imageData.getServer()
    session.hierarchy = imageData.getHierarchy()
    
    // Get all desired class annotations
    session.allClassAnnos = session.hierarchy.getAnnotationObjects().findAll {
        it.getPathClass()?.getName() == session.annoClass
    }
    
    // Getting annotations to classify/review/check
    def annotations = session.allClassAnnos.clone()
    
    // Getting channel specifics for toggling and turning off others ---
    def availableChannels = session.viewer.getImageDisplay().availableChannels()
    session.controllableChannels = availableChannels.findAll { ch ->
        session.toggleChannels.any { ch.getName().toUpperCase().contains( it.toUpperCase() ) }
    }
    def intitalDisplay = session.viewer.getImageDisplay()
    session.channelSpecifics = []
    session.controllableChannels.each { channel ->
        session.channelSpecifics << [
            channel: channel,
            color: channel.getColor(),
            name: channel.getOriginalChannelName(),
            state: channel in intitalDisplay.selectedChannels()
        ]
    }
    
    Platform.runLater {
        for (channel : availableChannels) {
            if (channel !in session.controllableChannels) {
                intitalDisplay.setChannelSelected( channel, false )
            }
        }
        session.viewer.repaintEntireImage()
    }
    
    // Storing selected channel(s) brightness info for when changing
    session.brightnessInfo = [:]
    Platform.runLater {
        for (channel : session.controllableChannels) {
            def channelDisplay = session.viewer.getImageDisplay()
            
            float minDisplay = channel.getMinDisplay()
            float maxDisplay = channel.getMaxDisplay()
            float maxAvailable = channel.getMaxAllowed()
            channelDisplay.autoSetDisplayRange( channel )
            float autoMinDisplay = channel.getMinDisplay()
            channelDisplay.setMinMaxDisplay( channel, minDisplay, maxDisplay )
            
            int nExpSteps = 50
            int nStdSteps = nExpSteps / 2
            float stdStepSize = maxAvailable / nStdSteps
            def expSteps = new float[ nExpSteps ]
            def stdSteps = new float[ nStdSteps ]
            for (i in 1..nExpSteps) {
               float expVal = (autoMinDisplay + 1) * Math.pow(maxAvailable / (autoMinDisplay + 1), i / nExpSteps)
               float stdVal = stdStepSize * i
               expSteps[i-1] = expVal
               if (stdVal <= maxAvailable) {
                   stdSteps[i-1] = stdVal
               }
            }
            
            session.brightnessInfo[ channel ] = [
                maxAvailable : maxAvailable,
                autoMinDisplay : autoMinDisplay,
                expSteps : expSteps,
                stdSteps : stdSteps
            ]
        }
    }
    
    // Changing positive annotation colors temporarily if desired
    def BLACK = ColorTools.makeRGB( 000, 000, 000 )
    
    if (session.classifierMode) {
        for (anno : annotations) {
            def value = anno.getMetadata().get( session.userKey )
            anno.setColor( session.statesAndColors.find { it.state == value }?.color ?: BLACK )
        }
    // Setting all annotations not to be reviewed to black
    } else {
        def nonNeededAnnos = session.allClassAnnos.clone() - annotations
        for (anno : nonNeededAnnos) { anno.setColor( BLACK ) }
    }
    
    // Turning off clicking and scrolling temporarily during the script to be brought back after
    session.scrollFilterKey = "scrollFilterON"
    session.scrollBlocker = { ScrollEvent e ->
        e.consume()  // stops QuPath from zooming
    } as EventHandler<ScrollEvent>
    addScrollBlocker( session )
    
    // Establishing/storing important variables
    @Field int numTrues
    @Field int numMixed
    numTrues = findAnnosByMdClassification( session.allClassAnnos, all = ["true"], any = [], diff = false, session ).size()
    numMixed = findAnnosByMdClassification( session.allClassAnnos, all = [], any = ["maybe"], diff = true, session ).size()
    def obj
    session.zoomed = false
    session.channelToChange = session.channelSpecifics.find { it.state }?.channel // Defaulting to first channel that's on
    def nonFreeViewerState
    boolean firstLoop = true
    
    
    resetSelection()
    session.pixelSize = server.getPixelCalibration().getAveragedPixelSizeMicrons()
    
    
    def sortedBins = []
    def annosToCheck = []
    
    int setBinIndex = 0
    int setAnnoIndex = 0
    
    currentImageLoop:
    while (true) {
        
        // Create and show the alert (control panel) once
        def alert = null
        runOnFxThreadAndWait {
            alert = new Alert( AlertType.NONE )
            alert.setX( dialogOffsetX )
            alert.setY( dialogOffsetY )
            alert.initModality( Modality.NONE )
        
            // Pass queue in so buttons put results instead of closing
            buildAndWireAlertUI( alert, session, uiElements, queue )
            updateUI( alert, session, uiElements, queue )
        
            alert.show()
            
            def window = alert.getDialogPane().getScene().getWindow()
            window.setOnCloseRequest { queue.put( ActionStrings.CANCEL ) }
            window.setAlwaysOnTop( true )
            window.focusedProperty().addListener { obs, wasFocused, isFocused ->
                if (!isFocused) { window.requestFocus() }
            }
        }
        
        // --- FOR NON-REVIEWER MODE: BIN CLASSIFICATIONS ---
        if (session.classifierMode) {
            
            // Bin setup
            def binMap = [:].withDefault { [] }
            for (anno : annotations) {
                double cX = anno.getROI().getCentroidX()
                double cY = anno.getROI().getCentroidY()
                int binX = cX * session.pixelSize / session.gridSize
                int binY = cY * session.pixelSize / session.gridSize
                def binKey = [binX, binY]
                binMap[ binKey ] << anno
            }
            sortedBins = binMap.keySet().sort { a, b -> a[0] <=> b[0] ?: a[1] <=> b[1] }
            
            // Storing classification data
            session.annoStateMap = [:]
            for (anno : annotations) {
                def value = anno.getMetadata().get( session.userKey )
                value ? session.annoStateMap.put( anno, value ) : session.annoStateMap.put( anno, session.statesAndColors[0].state )
            }
            
            // Group review (only review)
            for (int binIndex = setBinIndex; binIndex < sortedBins.size(); binIndex++) {
                
                def binKey = sortedBins[ binIndex ]
                def group = binMap[ binKey ]
                
                // Checking if annotations need classification (skipping if they do not)
                if (!session.checkingWork && firstLoop && group.every { it.getMetadata().containsKey( session.userKey ) }) continue
                
                // If wanted, classifed annos out of view will be filled (using detections)
                def detObjs = session.hierarchy.getDetectionObjects()
                def annoDetMap = detObjs.findAll { it.getMetadata()?.get( "annoID" ) }
                .collectEntries { det ->
                    [ (det.getMetadata().get( "annoID" )) : det ]
                }
                def annosIDsWithDet = annoDetMap.keySet()
                def dets = annoDetMap.values()
                def detsToAdd = []
                def detsToRemove = []
                
                // Changing color of already classified annos
                for (anno : annotations) {
                    def mdValue = anno.getMetadata().get( session.userKey )
                    def mapValue = session.annoStateMap[ anno ]
                    def id = anno.getID().toString()
                    
                    if (mdValue)            { anno.setColor( session.statesAndColors.find { it.state == mdValue }?.color ) }
                    else if (anno in group) { anno.setColor( session.statesAndColors[0].colorU ) }
                    else                    { anno.setColor( BLACK ) }
                    
                    // If wanted, classifed annos out of view will be filled (using detections)
                    if (mdValue && (anno !in group) && (id !in annosIDsWithDet)) {
                        def annoROI = anno.getROI()
                        def detObj = PathObjects.createDetectionObject( annoROI )
                        detObj.getMetadata().put( "annoID", id )
                        detObj.setColor( session.statesAndColors.find { it.state == mdValue }?.color )
                        detsToAdd << detObj
                    } else if ((anno in group) && (id in annosIDsWithDet)) {
                        def detForRemoval = annoDetMap[ id ]
                        detsToRemove << detForRemoval
                    }
                }
                
                session.hierarchy.insertPathObjects( detsToAdd )
                session.hierarchy.removeObjects( detsToRemove, true )
                Platform.runLater { session.viewer.repaintEntireImage() }
                
                // Calcualating bin coordinates for viewer centering
                double binX = (session.gridSize / session.pixelSize) * (binKey[0] + 0.5)
                double binY = (session.gridSize / session.pixelSize) * (binKey[1] + 0.5)
                
                // Focus viewer on group
                changeViewer( binX, binY, session.gridSize, session.gridSize, true, session )
                
                // --- Loop if adjusting settings without moving to next group ---
                annoGroupLoop:
                while (true) { 
                    
                    // Updating control panel
                    Platform.runLater {
                        alert.setTitle(!session.freeView ?
                            "Group ${binIndex + 1} of ${sortedBins.size()} — ${session.userName}" :
                            "Free View")
                            
                            updateUI( alert, session, uiElements, queue )
                    }
                    
                    def result = queue.take()
                    
                    
                    // -- Checking/acting on user response --
                    switch (result) {
                        
                        case (ActionStrings.CANCEL):
                            runOnFxThreadAndWait { alert.getDialogPane()?.getScene()?.getWindow()?.hide() }
                            restoreToPrescript( session )
                            changeGrid( wasGridOn, gridX, gridY, session )
                            codePreferences.instructions = session.instructions
                            writePreferencesMaptoJSON( session.prefsFile, codePreferences )
                            return
                        
                        case (ActionStrings.INSTRUCTIONS):
                            session.instructions = !session.instructions
                            break
                        
                        case (ActionStrings.BACK):
                            if (binIndex > 0) {
                                binIndex -= 2
                                break annoGroupLoop
                            } else {
                                session.message = "Already at first group."
                                conditionallyFireMessage( session )
                            }
                            break
                            
                        case (ActionStrings.TOGGLE_FOCUS): // Looking for annotation mouse is over
                            List toggleableAnnos = session.freeView ? annotations : group
                            toggleFocus( session, toggleableAnnos )  
                            break
                            
                        case (ActionStrings.TOGGLE_CLASS):
                            def mouseCoords = getCursorPosition( session )
                            for (anno in group) {
                                def roi = anno.getROI()
                                
                                // Check if the mouse point lies inside the selected ROI
                                if (roi.contains( mouseCoords.x, mouseCoords.y )) {
                                    changeClassification( anno, session )
                                    break
                                }
                            }
                            break
                            
                        case (ActionStrings.TOGGLE_ANNOS): // Toggling annotations on or off
                            getAnnosVisibilityStatus( session ) ? 
                                session.prevSelectedAnnos = getSelectedObjects().collect { it } : 
                                selectObjects( session.prevSelectedAnnos )
                            toggleAnnos( session )
                            break
                       
                        case (ActionStrings.TOGGLE_CHANNEL): // Toggles channel of interest or remembers for changing brightness (if turned off, finds first one that's on)
                            def channelToToggle = session.channelSpecifics.find { it.name == session.toggleChannel }?.channel
                            toggleChannel( channelToToggle, session )
                            boolean toggledChannelState = session.channelSpecifics.find { it.channel == channelToToggle }?.state
                            boolean brightnessChannelState = session.channelSpecifics.find { it.channel == session.channelToChange }?.state
                            def backupChannel = session.channelSpecifics.find { it.state }?.channel
                            
                            if (toggledChannelState) {
                                session.channelToChange = channelToToggle
                            } else {
                                session.channelToChange = brightnessChannelState ? session.channelToChange : backupChannel
                            }
                            break
                        
                        case (ActionStrings.BRIGHT_UP):
                            changeBrightness( "UP", session )
                            break
                        
                        case (ActionStrings.BRIGHT_DOWN):
                            changeBrightness( "DOWN", session )
                            break
                        
                        case (ActionStrings.REMOVE):
                            for (anno in group) {
                                anno.getMetadata().remove( session.userKey )
                                session.annoStateMap.put( anno, session.statesAndColors[0].state )
                                anno.setColor ( session.statesAndColors[0].colorU )
                            }
                            Platform.runLater { session.viewer.repaintEntireImage() }
                            break
                        
                        case (ActionStrings.TOGGLE_FREE_VIEW):
                            session.freeView = !session.freeView
                            
                            Platform.runLater {
                                if (session.freeView) {
                                    // Storing existing colors for anno before any change
                                    session.allClassAnnos.each { session.currentAnnoColorsMap[ it ] = it.getColor() }
                                    
                                    // Getting/generating/setting colors of different anno classifications
                                    colorAnnosFromMap( session, [session.userKey] )
                                    
                                    session.overlayOptions.setShowDetections( false )
                                    session.overlayOptions.setShowGrid( false )
                                    session.prevSelectedAnnos = getSelectedObjects().collect { it }
                                    session.hierarchy.getSelectionModel().clearSelection()
                                    nonFreeViewerState = saveViewerState( session )
                                    session.viewer.zoomToFit()
                                    removeScrollBlocker( session )
                                    
                                } else {
                                    
                                    restoreViewerState( nonFreeViewerState, session )
                                    session.overlayOptions.setShowDetections( true )
                                    session.overlayOptions.setShowGrid( true )
                                    session.overlayOptions.setOpacity( 1 )
                                    session.freeViewMode = session.freeViewModes[0]
                                    session.allClassAnnos.each { it.setColor( session.currentAnnoColorsMap[ it ] ) }                        
                                    selectObjects( session.prevSelectedAnnos )
                                    addScrollBlocker( session )
                                    session.zoomed = false
                                }
                            }
                            break
                            
                        case (ActionStrings.SWITCH_FREE_VIEW_MODE):
                            switchFVmode( session )
                            break
                            
                        case (ActionStrings.CENTER_VIEWER):
                            def mouseCoords = getCursorPosition( session )
                            Platform.runLater { session.viewer.setCenterPixelLocation( mouseCoords.x, mouseCoords.y ) }
                            break
                        
                        case (ActionStrings.NEXT): // Updating classifications after each group
                            for (anno : group) {
                                def state = session.annoStateMap.get( anno )
                                anno.getMetadata().put( session.userKey, state )
                            }
                            break annoGroupLoop
                    }
                    firstLoop = false
                }
                firstLoop = false
            }
        
        
        // --- FOR REVIEWER MODE: ANNOTATIONS INDIVIDUALLY ---
        } else if (session.reviewerMode) {
            
            annotations = findAnnosByMdClassification(
                session.allClassAnnos,
                all     = session.reviewerCriteria.all,
                any     = session.reviewerCriteria.any,
                diff    = session.reviewerCriteria.different,
                session = session
            )
            
            filterForUserKey( annotations, session.checkingWork ? true : false, session )
            
            // Changing color of already classified annos
            for (anno in annotations) {
                def value = anno.getMetadata().get( session.userKey )
                if (value in session.reviewerStates) {
                    anno.setColor( session.statesAndColors.find { it.state == value }.color )
                }
            }
            
            // Individual annotation review
            for (int annoIndex = setAnnoIndex; annoIndex < annotations.size(); annoIndex++) {
                
                def annotation = annotations[ annoIndex ]
                
                // Skipping to annotations that needs review if others alrady have been
                if (firstLoop && annotation.getMetadata().containsKey( session.userKey )) continue
                
                Platform.runLater { session.viewer.repaintEntireImage() }
                
                // Focusing on specific annotation
                def roi = annotation.getROI()
                changeViewer( roi.getCentroidX(), roi.getCentroidY(), roi.getBoundsWidth() * 2, roi.getBoundsHeight() * 2, false, session )
                selectObjects( annotation )
                
                // --- Loop if adjusting settings without moving to next group ---
                annoLoop:
                while (true) {
                    
                    // Updating control panel
                    Platform.runLater {
                        alert.setTitle( !session.freeView ? 
                            "Annotation ${annoIndex + 1} of ${annotations.size()} — ${session.userName}" :
                            "Free View" )
                    
                        updateUI( alert, session, uiElements, queue )
                    }
                    
                    def result = queue.take()
                    
                    // -- Checking/acting on user response --
                    switch (result) {
                        
                        case (ActionStrings.CANCEL): // If cancelled, puts back previous settings
                            runOnFxThreadAndWait { alert.getDialogPane()?.getScene()?.getWindow()?.hide() }
                            restoreToPrescript( session )
                            changeGrid( wasGridOn, gridX, gridY, session )
                            codePreferences.instructions = session.instructions
                            writePreferencesMaptoJSON( session.prefsFile, codePreferences )
                            return
                        
                        case (ActionStrings.INSTRUCTIONS):
                            session.instructions = !session.instructions
                            break
                        
                        case (ActionStrings.BACK):
                            if (annoIndex > 0) {
                                annoIndex -= 2
                                break annoLoop
                            } else {
                                session.message = "Already at first annotation."
                                conditionallyFireMessage( session )
                                break
                            }
                        case (ActionStrings.TOGGLE_ANNOS): // Toggling annotations on or off
                            getAnnosVisibilityStatus( session ) ? 
                                session.prevSelectedAnnos = getSelectedObjects().collect { it } : 
                                selectObjects( session.prevSelectedAnnos )
                            toggleAnnos( session )
                            break
                        
                        case (ActionStrings.TOGGLE_CHANNEL): // Toggles channel of interest or remembers for changing brightness (if turned off, finds first one that's on)
                            def channelToToggle = session.channelSpecifics.find { it.name == session.toggleChannel }?.channel
                            toggleChannel( channelToToggle, session )
                            boolean toggledChannelState = session.channelSpecifics.find { it.channel == channelToToggle }?.state
                            boolean brightnessChannelState = session.channelSpecifics.find { it.channel == session.channelToChange }?.state
                            def backupChannel = session.channelSpecifics.find { it.state }?.channel
                            
                            if (toggledChannelState) {
                                session.channelToChange = channelToToggle
                            } else {
                                session.channelToChange = brightnessChannelState ? session.channelToChange : backupChannel
                            }
                            break
                        
                        case (ActionStrings.BRIGHT_UP):
                            changeBrightness( "UP", session )
                            break
                        
                        case (ActionStrings.BRIGHT_DOWN):
                            changeBrightness( "DOWN", session )
                            break
                        
                        case (ActionStrings.TOGGLE_FREE_VIEW):
                            session.freeView = !session.freeView
                            
                            Platform.runLater {
                                if (session.freeView) {
                                    nonFreeViewerState = saveViewerState( session )
                                    
                                    // Storing existing colors for anno before any change
                                    session.allClassAnnos.each { session.currentAnnoColorsMap[ it ] = it.getColor() }
                                    
                                    // Getting/generating/setting colors of different anno classifications
                                    colorAnnosFromMap( session, session.classifierKeys )
                                    
                                    session.prevSelectedAnnos = getSelectedObjects().collect { it }
                                    session.hierarchy.getSelectionModel().clearSelection()
                                    session.viewer.zoomToFit()
                                    session.overlayOptions.setShowGrid( false )
                                    removeScrollBlocker( session )
                                    
                                } else {
                                    
                                    restoreViewerState( nonFreeViewerState, session )
                                    session.overlayOptions.setOpacity( 1 )
                                    session.freeViewMode = session.freeViewModes[0]
                                    session.allClassAnnos.each { it.setColor( session.currentAnnoColorsMap[ it ] ) }                          
                                    selectObjects( session.prevSelectedAnnos )
                                    session.overlayOptions.setShowGrid( true )
                                    addScrollBlocker( session )
                                }
                            }
                            break
                        
                        // Switch scroll mode in free view between zoom and brightness
                        case (ActionStrings.SWITCH_FREE_VIEW_MODE):
                            switchFVmode( session )
                            break
                        
                        // Zoom in on specific annotation
                        case (ActionStrings.TOGGLE_FOCUS):
                            toggleFocus( session, session.allClassAnnos )                 
                            break
                        
                        case (ActionStrings.CENTER_VIEWER):
                            def mouseCoords = getCursorPosition( session )
                            Platform.runLater { session.viewer.setCenterPixelLocation( mouseCoords.x, mouseCoords.y ) }
                            break
                        
                        case (ActionStrings.SET_CLASS ):
                            def md = annotation.getMetadata()
                            String setClass = session.reviewerClassification
                            md.put( session.userKey, setClass )
                            annotation.setColor( session.statesAndColors.find { it.state == setClass }.color )
                            break annoLoop
                    }
                    firstLoop = false
                }
                firstLoop = false
            }
        }
        
        runOnFxThreadAndWait { alert.getDialogPane()?.getScene()?.getWindow()?.hide() }
        
        fireHierarchyUpdate()
        
        
        // --- Final message and prompt for next image ---
        def moveToNextImage = runOnFxThreadAndWait {
            int imagesDone = imageIndex + 1
            int imagesToDo = session.imagesToProcess.size() - imagesDone
            alert = new Alert( Alert.AlertType.NONE )
            alert.setTitle( "Image complete! (${imagesToDo} images left)" )
            alert.setHeaderText( null )
            alert.setContentText( "Save classifications and move to next image?" )
            
            // Customize buttons to Yes and No only
            boolean anyFinishedImageAnnos = session.allClassAnnos.any { anno ->
                boolean hasUserKey = anno.getMetadata().containsKey( session.userKey )
                return hasUserKey
            }
            if (annotations.isEmpty() && !anyFinishedImageAnnos) {
                alert.getButtonTypes().setAll( ButtonType.YES, ButtonType.NO )
            } else {
                alert.getButtonTypes().setAll(
                    ButtonType.YES,
                    ButtonType.NO,
                    new ButtonType( ActionStrings.BACK_TO_LAST, ButtonData.BACK_PREVIOUS ),
                    new ButtonType( ActionStrings.REVIEW_ALL, ButtonData.HELP_2 )
                )
            }
            
            def result = alert.showAndWait()
            return result.get().getText()
        }
        
        // If next image wanted, saving changes and changing image
        switch (moveToNextImage) {
            case (ActionStrings.YES):
                restoreToPrescript( session )
                changeGrid( wasGridOn, gridX, gridY, session )
                codePreferences.instructions = session.instructions
                writePreferencesMaptoJSON( session.prefsFile, codePreferences )
                
                def entry = project.getEntry( imageData )
                entry.saveImageData( imageData )
                project.syncChanges()
                
                break currentImageLoop
            
            // Stopping script and resetting colors if not
            case (ActionStrings.NO):
                restoreToPrescript( session )
                changeGrid( wasGridOn, gridX, gridY, session )
                codePreferences.instructions = session.instructions
                writePreferencesMaptoJSON( session.prefsFile, codePreferences )
                return
            
            // Back to last group or anno if done
            case (ActionStrings.BACK_TO_LAST):
                setBinIndex = sortedBins.size() - 1
                setAnnoIndex = annotations.size() - 1
                firstLoop = false
                break
            
            // Back to first group or anno if done
            case (ActionStrings.REVIEW_ALL):
                setBinIndex = 0
                setAnnoIndex = 0
                firstLoop = false
                break
        }
    }
}

// Finished with project message
runOnFxThreadAndWait {
    def doneAlert = new Alert( AlertType.INFORMATION )
    doneAlert.setTitle( "Finished" )
    doneAlert.setHeaderText( "Project complete!" )
    doneAlert.setContentText( null )
    doneAlert.show()
}


// --- Importing packages ---
import groovy.transform.Field

import java.awt.MouseInfo
import java.awt.geom.Point2D
import java.awt.Color as AwtColor
import java.util.Collections
import javafx.util.StringConverter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import javafx.animation.PauseTransition
import javafx.animation.FadeTransition
import javafx.util.Duration
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.collections.FXCollections
import javafx.event.*
import javafx.geometry.*
import javafx.scene.*
import javafx.scene.control.*
import javafx.scene.control.Alert.AlertType
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.paint.Color
import javafx.scene.input.*
import javafx.scene.layout.*
import javafx.scene.text.*
import javafx.scene.shape.*
import javafx.stage.*

import qupath.lib.common.ColorTools
import qupath.lib.gui.tools.IconFactory
import qupath.lib.gui.viewer.QuPathViewer
import qupath.lib.gui.viewer.QuPathViewerListener

import javafx.beans.binding.Bindings

import com.google.gson.GsonBuilder
import com.google.gson.Gson

import org.controlsfx.glyphfont.FontAwesome


// Comments for tool launcher icon and tooltip
// ORDER: 4
// ICON: PENCIL_SQUARE_ALT
// Manually classify annotations