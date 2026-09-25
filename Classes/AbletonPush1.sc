AbletonPush1 {
	var <midiOut, midiIn;
	var displayCache, padColorCache, <>displayMode;
	var buttonUpFuncs, buttonDownFuncs, <>padOnFunc, <>padOffFunc, <>padVelFunc, <displayFunc, <dispJack, <>encoderFunc, <>ribbonFunc;
	var <>pedal1Func;

	*new {
		^super.newCopyArgs().init()
	}

	*getProgressBar {|value| // between 0-1.0
		var steps = (value.clip(0, 1) * 32).trunc.asInteger;
		^Array.fill(8, { |i|
			var local = (steps - (i * 4)).clip(0, 4);
			switch(local,
				0, { 6 }, // --
				1, { 4 }, // -|
				2, { 3 }, // |-
				3, { 5 }, // ||
				4, { 5 }  // ||
			)
		})
	}

	*buttonCodes { ^[
		\play, 85,
		\rec, 86,
		\new, 87,
		\duplicate, 88,
		\automation, 89,
		\fixed_length, 90,
		\double, 117,
		\delete, 118,
		\undo, 119,
		\metronome, 9,
		\tap_tempo, 3,
		'1/4', 36,
		'1/4t', 37,
		'1/8',38,
		'1/8t', 39,
		'1/16',40,
		'1/16t',41,
		'1/32', 42,
		'1/32t', 43,
		\stop, 29,
		\master, 28,
		'arrow_down', 47,
		'arrow_up', 46,
		'arrow_right', 45,
		'arrow_left', 44,
		\select, 48,
		\shift, 49,
		\note, 50,
		\session, 51,
		\add_effect, 52,
		\add_track, 53,
		\octave_down, 54,
		\octave_up,55,
		\repeat, 56,
		\accent, 57,
		\scales, 58,
		\user, 59,
		\mute, 60,
		\solo, 61,
		\device, 110,
		\browse, 11,
		\track, 112,
		\clip, 113,
		\volume, 114,
		\pan_send, 115
	].asDict
	}

	*buttonKeys { ^this.buttonCodes.keys }

	*buttonCCs { ^this.buttonCodes.values }

	*pushConnected { MIDIIn.connectAll; ^MIDIIn.findPort("Ableton Push", "User Port").notNil }


	init {
		this.initMidiPort;
		midiOut.sysex(Int8Array[240,71,127,21,92,0,1,0,247]); // set note aftertouch
		midiOut.sysex(Int8Array[240,71,127,21,99,0,1,9 /*0-10*/ ,247]); // set ribbon to modwheel

		padColorCache = (0!3)!64;

		displayCache = 0!68!4; // 32=Char.space.ascii
		buttonUpFuncs = IdentityDictionary[];
		buttonDownFuncs = IdentityDictionary[];
		displayFunc = { this.toBlocks([]) };
		encoderFunc = {};
		padOnFunc = {};
		padOffFunc = {};
		padVelFunc = {};
		ribbonFunc = {};
		pedal1Func = {};
		displayMode = \blocks; // blocks / continous

		this.makeMidiFuncs;
		dispJack = Task({ loop { this.setDisplayRows(displayFunc.()); 0.2.wait } }).play
	}

	initMidiPort {
		MIDIClient.init(); MIDIIn.connectAll;
		switch(thisProcess.platform.name)
		{\osx} {
			"mac os".postln;
			midiOut = MIDIOut.newByName("Ableton Push", "User Port");
			midiIn = MIDIIn.findPort("Ableton Push", "User Port");
		}
		{\linux } {
			"linux".postln;
			midiOut = MIDIOut.findPort("Ableton Push", "Ableton Push User Port");
			midiOut.latency_(0);
			midiIn = MIDIIn.findPort("Ableton Push", "Ableton Push User Port");
		};
	}

	makeMidiFuncs {

		MIDIFunc.cc({|val, cc|
			var key = AbletonPush1.buttonCodes.invert[cc];
			if(val == 127, { buttonDownFuncs[key].value }, { buttonUpFuncs[key].value });
		}, AbletonPush1.buttonCCs,0).permanent_(true);
		MIDIFunc.noteOn({|vel, note| padOnFunc.value(note-36, vel) }, (36..99),0).permanent_(true);
		MIDIFunc.noteOff({|vel, note| padOffFunc.value(note-36, vel) }, (36..99),0).permanent_(true);
		MIDIFunc.polytouch({|vel, note| padVelFunc.value(note-36, vel)}, (36..99), 0).permanent_(true);
		MIDIFunc.cc({|val| ribbonFunc !? { ribbonFunc.(val.linlin(0, 127,0, 1.0)) } }, 1,0).permanent_(true);
		MIDIFunc.cc({|val, num| encoderFunc.value(num-71, val)}, (71..79)).permanent_(true);
		MIDIFunc.cc({|val| pedal1Func.value(val) },69,0)
	}


	setPadColor {|padNum, r, g, b|
		var r1 = (r/16).round;
		var r2 = r % 16;
		var g1 = (g/16).round;
		var g2 = g % 16;
		var b1 = (b/16).round;
		var b2 = b % 16;
		if(padColorCache[padNum] != [r,g,b], {
			midiOut.sysex(Int8Array[240,71,127,21,4,0,8,padNum,0,r1, r2, g1, g2, b1, b2, 247]);
			padColorCache[padNum] = [r,g,b]
		});
	}

	clearPads {
		64.do { |i| this.setPadColor(i, 0, 0, 0) }
	}

	setDisplayRows  {|newRows|
		var tailingOldChars, oldRow; // oldChars: Chars in the message that are old, so sent without need
		var msg, msgStart;
		var sendSysex = {|row, offset, chars|
			midiOut.sysex(Int8Array.newFrom([240, 71, 127, 21] ++ [24 + row, 0, chars.size + 1, offset] ++ chars ++ [247]));
		};

		newRows = newRows.extend(4, []).collect(_.extend(68, 32));
		newRows.do {|row, rowIndx|
			oldRow = displayCache[rowIndx];
			tailingOldChars = 0; msg=nil; msgStart =nil;
			row.do{|newChar,i|
				if(msg.notNil,{
					if(oldRow[i]==newChar, { tailingOldChars = tailingOldChars+1 }, {tailingOldChars = 0 });
					msg.add(newChar);
				}, { if(oldRow[i]!=newChar, { msg=List[newChar]; msgStart=i }) });
				if(tailingOldChars > 9) {
					if(msg.size > 9) { sendSysex.(rowIndx,msgStart, msg.copy[0..(msg.size-9)]) };
					msgStart = nil; msg = nil;
				};
				if(i>66 and: { msg.notNil }, { sendSysex.(rowIndx, msgStart, msg) });
				newChar
			}
		};
		displayCache = newRows
	}

	toBlocks{|rows| /// this should be a class method, but the class name is too long :--)
		var offset = [0,9,17,26,34,43,51,60];
		var spaces = [8,25,42,59];
		var res;
		var cells;
		rows = rows.extend(4,[]).collect{|row|
			row.collect{|block|
				block = block.value;
				if(block.isKindOf(String), { block.ascii }, {block})
			}.extend(8, [])
		};
		cells = 4.collect{|row|
			res = 8.collect{|coll| rows[row][coll].extend(8,32).at((0..7)) }.flatten;
			spaces.do{|indx| res = res.insert(indx, 32)}; res
		};
		^cells
	}

	displayFunc_ {|newFunc| displayFunc = newFunc; dispJack.reset.play }

	clearLine { |line| midiOut.sysex(Int8Array[240,71,127,21,28+line,0,0,247]) }

	clearDisplay{ 4.do{ |l| this.clearLine(l) } }

	clearBlock{ |row, block| this.writeAscii(row, block, 32!8) }

	registerButton {|key, func, triggerWhen=\up, ledmode=\on|
		AbletonPush1.buttonCodes[key] ?? { "no button with this name found".throw };
		switch(triggerWhen,
			\up, { buttonUpFuncs[key] = func },
			\down,{ buttonDownFuncs[key] = func });
		midiOut.control(0, AbletonPush1.buttonCodes[key], 4)
	}

	unregisterButton {|key|
		buttonUpFuncs.removeAt(key); buttonDownFuncs.removeAt(key);
		midiOut.control(0, AbletonPush1.buttonCodes[key], 0)
	}

}