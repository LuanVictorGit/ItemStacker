package me.itemstacker.managers;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import me.itemstacker.Core;
import me.itemstacker.utils.Task;

@Getter
public class Manager {

	private final List<Task> tasks = new ArrayList<>();
	
	public static Manager get() {return Core.getInstance().getManager();}
	
}
